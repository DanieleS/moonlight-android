package com.limelight.bitrate;

import com.limelight.bitrate.AdaptiveBitrateController.Mode;
import com.limelight.bitrate.AdaptiveBitrateController.Sample;
import com.limelight.nvstream.http.HostHttpResponseException;

import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LiveBitrateTest {

    /** Runs each task on the caller's thread, so the tests need no waiting. */
    private static class InlineExecutor extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new RejectedExecutionException();
            }
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    private static class FakeHost implements LiveBitrate.Host {
        boolean supported = true;
        /** What to throw from /bitrate, or null to apply the request. */
        IOException failure;
        /** How many requests {@link #failure} is thrown for; negative for all of them. */
        int failuresLeft = -1;
        /**
         * The host's max_bitrate, 0 for none. A live change is clamped to it as is, the way
         * Vibepollo's /bitrate does; a starting stream's budget is clamped to it before the
         * deductions, see {@link #startEncoderKbps}.
         */
        int maxEncoderKbps;
        int capabilityQueries;
        final List<Integer> requests = new ArrayList<>();

        @Override
        public boolean supportsRuntimeBitrate() {
            capabilityQueries++;
            return supported;
        }

        @Override
        public int setRuntimeBitrate(int encoderKbps) throws IOException {
            requests.add(encoderKbps);
            if (failure != null && failuresLeft != 0) {
                if (failuresLeft > 0) {
                    failuresLeft--;
                }
                throw failure;
            }
            return maxEncoderKbps > 0 ? Math.min(encoderKbps, maxEncoderKbps) : encoderKbps;
        }
    }

    private static class RecordingListener implements LiveBitrate.Listener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onBitrateChanged(int kbps, boolean adaptive) {
            events.add((adaptive ? "adaptive " : "manual ") + kbps);
        }

        @Override
        public void onBitrateChangeFailed(int kbps) {
            events.add("failed " + kbps);
        }

        @Override
        public void onAdaptiveStopped(boolean restored) {
            events.add("adaptive stopped" + (restored ? ", restored" : ""));
        }
    }

    private final FakeHost host = new FakeHost();
    private final RecordingListener listener = new RecordingListener();
    private long now;

    private LiveBitrate create(int startKbps) {
        return create(startKbps, 0);
    }

    private LiveBitrate create(int startKbps, int warpFactor) {
        return new LiveBitrate(host, listener, Mode.BALANCED, 60, 2, startKbps, warpFactor,
                new InlineExecutor(), Runnable::run, () -> now);
    }

    private static int encoder(int kbps, int startKbps) {
        return BitrateSteps.toEncoderKbps(kbps, 2, startKbps, 0);
    }

    /** What the host's encoder starts at, its max_bitrate applied to the whole budget first. */
    private int startEncoderKbps(int startKbps) {
        int wire = host.maxEncoderKbps > 0 ? Math.min(startKbps, host.maxEncoderKbps) : startKbps;
        return encoder(wire, startKbps);
    }

    private static Sample loss(float percent) {
        return new Sample(percent, 4, 3f);
    }

    /** Feed one sample a second until {@code until} ms. */
    private void feed(LiveBitrate bitrate, Sample sample, long until) {
        while (now < until) {
            now += 1000;
            bitrate.onSample(sample);
        }
    }

    @Test
    public void aHostWithoutTheEndpointIsSentNothingElse() {
        host.supported = false;
        LiveBitrate bitrate = create(40000);
        bitrate.start(true);

        assertFalse(bitrate.isSupported());
        assertFalse(bitrate.isAdaptive());
        feed(bitrate, loss(50f), 20000);
        bitrate.setManual(20000);
        bitrate.setAdaptive(true);
        bitrate.setAdaptive(false);

        assertEquals(1, host.capabilityQueries);
        assertTrue(host.requests.isEmpty());
        assertTrue(listener.events.isEmpty());
        assertEquals(40000, bitrate.getCurrentKbps());
    }

    @Test
    public void adaptationIsOptIn() {
        LiveBitrate bitrate = create(40000);
        bitrate.start(false);

        assertTrue(bitrate.isSupported());
        assertFalse(bitrate.isAdaptive());
        feed(bitrate, loss(50f), 20000);
        assertTrue(host.requests.isEmpty());
    }

    @Test
    public void aManualChoiceGoesToTheEncoderAfterTheHostsDeductions() {
        LiveBitrate bitrate = create(40000);
        bitrate.start(false);
        bitrate.setManual(20000);

        assertEquals(Collections.singletonList(encoder(20000, 40000)), host.requests);
        assertEquals(20000, bitrate.getCurrentKbps());
        assertEquals(20000, bitrate.getChosenKbps());
        assertEquals(Collections.singletonList("manual 20000"), listener.events);
    }

    @Test
    public void aRefusedManualChoiceChangesNothing() {
        LiveBitrate bitrate = create(40000);
        bitrate.start(false);
        host.failure = new HostHttpResponseException(404, "No active session for this client");
        bitrate.setManual(20000);

        assertEquals(40000, bitrate.getCurrentKbps());
        assertEquals(40000, bitrate.getChosenKbps());
        assertTrue(bitrate.isSupported());
        assertEquals(Collections.singletonList("failed 20000"), listener.events);
    }

    @Test
    public void lossLowersTheBitrateThroughTheHost() {
        LiveBitrate bitrate = create(40000);
        bitrate.start(true);
        assertTrue(bitrate.isAdaptive());

        feed(bitrate, loss(12f), AdaptiveBitrateController.START_GRACE_MS);
        assertEquals(Collections.singletonList(encoder(28000, 40000)), host.requests);
        assertEquals(28000, bitrate.getCurrentKbps());
        // The chosen bitrate stays: it is the ceiling to come back up to.
        assertEquals(40000, bitrate.getChosenKbps());
        assertEquals(Collections.singletonList("adaptive 28000"), listener.events);
    }

    @Test
    public void aManualChoiceWhileAdaptingBecomesTheCeiling() {
        LiveBitrate bitrate = create(40000);
        bitrate.start(true);
        bitrate.setManual(20000);
        host.requests.clear();

        feed(bitrate, new Sample(0f, 4, 3f), 120000);
        assertTrue(host.requests.isEmpty());
        assertEquals(20000, bitrate.getCurrentKbps());
    }

    @Test
    public void switchingAdaptationOffGoesBackToTheChosenBitrate() {
        LiveBitrate bitrate = create(40000);
        bitrate.start(true);
        feed(bitrate, loss(12f), AdaptiveBitrateController.START_GRACE_MS);
        assertEquals(28000, bitrate.getCurrentKbps());

        bitrate.setAdaptive(false);
        assertFalse(bitrate.isAdaptive());
        assertEquals(40000, bitrate.getCurrentKbps());
        assertEquals(encoder(40000, 40000), (int) host.requests.get(host.requests.size() - 1));

        int sent = host.requests.size();
        feed(bitrate, loss(50f), now + 20000);
        assertEquals(sent, host.requests.size());
    }

    @Test
    public void aMissingEndpointEndsItForTheStream() {
        LiveBitrate bitrate = create(40000);
        bitrate.start(true);
        host.failure = new FileNotFoundException("/bitrate");

        feed(bitrate, loss(12f), AdaptiveBitrateController.START_GRACE_MS);
        assertEquals(1, host.requests.size());
        assertFalse(bitrate.isSupported());
        assertFalse(bitrate.isAdaptive());

        bitrate.setManual(20000);
        feed(bitrate, loss(8f), now + 20000);
        assertEquals(1, host.requests.size());
    }

    @Test
    public void adaptationGivesUpAfterRepeatedFailures() {
        LiveBitrate bitrate = create(40000);
        bitrate.start(true);
        host.failure = new IOException("timeout");

        feed(bitrate, loss(12f), 60000);
        assertEquals(LiveBitrate.MAX_ADAPTIVE_FAILURES, host.requests.size());
        assertFalse(bitrate.isAdaptive());
        // The host may still take a change by hand.
        assertTrue(bitrate.isSupported());
        // Nothing was changed, so the stream is still at the chosen bitrate, and says so.
        assertEquals(Collections.singletonList("adaptive stopped, restored"), listener.events);
    }

    @Test
    public void givingUpGoesBackToTheChosenBitrateAndSaysSo() {
        LiveBitrate bitrate = create(40000);
        bitrate.start(true);
        feed(bitrate, loss(12f), AdaptiveBitrateController.START_GRACE_MS);
        assertEquals(28000, bitrate.getCurrentKbps());

        host.failure = new HostHttpResponseException(404, "No active session for this client");
        host.failuresLeft = LiveBitrate.MAX_ADAPTIVE_FAILURES;
        feed(bitrate, loss(12f), now + 60000);

        assertFalse(bitrate.isAdaptive());
        assertEquals(40000, bitrate.getCurrentKbps());
        assertEquals(encoder(40000, 40000), (int) host.requests.get(host.requests.size() - 1));
        assertEquals(Arrays.asList("adaptive 28000", "manual 40000", "adaptive stopped, restored"),
                listener.events);
    }

    @Test
    public void givingUpWhenTheHostTakesNothingSaysSoToo() {
        LiveBitrate bitrate = create(40000);
        bitrate.start(true);
        feed(bitrate, loss(12f), AdaptiveBitrateController.START_GRACE_MS);
        host.failure = new IOException("timeout");
        feed(bitrate, loss(12f), now + 60000);

        assertFalse(bitrate.isAdaptive());
        assertEquals(28000, bitrate.getCurrentKbps());
        assertEquals(Arrays.asList("adaptive 28000", "adaptive stopped"), listener.events);
    }

    @Test
    public void aHostCapStopsProbingAboveIt() {
        // The host's max_bitrate is 30000: the stream started at what 30 Mbps works out to.
        host.maxEncoderKbps = 30000;
        LiveBitrate bitrate = create(40000);
        bitrate.start(true);
        feed(bitrate, loss(3f), AdaptiveBitrateController.START_GRACE_MS + 1000);
        // A 10% step from 40000 asks the encoder for less than the limit, so it can't give the
        // limit away (nor can anything this end knows; see the risks of the change).
        assertEquals(36000, bitrate.getCurrentKbps());

        // The first probe up asks for more than 30000: clamped, and that gives the limit away.
        // It is asked again at the limit, where a fresh stream would start.
        feed(bitrate, new Sample(0f, 4, 3f), now + 300000);
        assertEquals(30000, bitrate.getCurrentKbps());
        int requestsAfterSettling = host.requests.size();
        feed(bitrate, new Sample(0f, 4, 3f), now + 300000);
        assertEquals(requestsAfterSettling, host.requests.size());
        for (int request : host.requests.subList(2, host.requests.size())) {
            assertTrue(request <= startEncoderKbps(40000));
        }
    }

    @Test
    public void aHostLimitBelowTheStartNeverRaisesTheEncoderForLoss() {
        // 150 Mbps chosen, the host's max_bitrate at 50000: the encoder started at 38988.
        host.maxEncoderKbps = 50000;
        LiveBitrate bitrate = create(150000);
        bitrate.start(true);
        int started = startEncoderKbps(150000);
        assertEquals(38988, started);

        feed(bitrate, loss(12f), AdaptiveBitrateController.START_GRACE_MS);
        // The first request (105000, to the encoder 82988) is clamped to 50000 and gives the limit
        // away; at once the cut is asked again from the limit: 30% off 50000.
        assertEquals(2, host.requests.size());
        assertEquals(35000, bitrate.getCurrentKbps());
        assertEquals(encoder(35000, 150000), (int) host.requests.get(1));
        assertTrue(host.requests.get(1) < started);

        // Further loss keeps cutting from there, never back up to the limit.
        feed(bitrate, loss(12f), now + 10000);
        for (int request : host.requests.subList(1, host.requests.size())) {
            assertTrue(request < started);
        }
        // Clean again, it climbs back to where a fresh stream would start, and no further.
        feed(bitrate, new Sample(0f, 4, 3f), now + 600000);
        assertEquals(50000, bitrate.getCurrentKbps());
        for (int request : host.requests.subList(1, host.requests.size())) {
            assertTrue(request <= started);
        }
    }

    @Test
    public void onceTheHostLimitIsKnownAManualPickIsHeldToIt() {
        host.maxEncoderKbps = 50000;
        LiveBitrate bitrate = create(150000);
        bitrate.start(false);
        bitrate.setManual(120000);
        // Clamped, then asked again at the limit, which is where a fresh stream at 120 would be.
        assertEquals(Arrays.asList(encoder(120000, 150000), encoder(50000, 150000)), host.requests);
        assertEquals(50000, bitrate.getCurrentKbps());
        assertEquals(Collections.singletonList("manual 50000"), listener.events);

        host.requests.clear();
        bitrate.setManual(100000);
        assertEquals(Collections.singletonList(encoder(50000, 150000)), host.requests);
    }

    @Test
    public void warpMultipliesWhatIsSent() {
        LiveBitrate bitrate = create(40000, 2);
        bitrate.start(false);
        bitrate.setManual(40000);
        // The host doubled the starting budget for warp; the same bitrate must land in the same place.
        assertEquals(Collections.singletonList(encoder(80000, 40000)), host.requests);
    }

    @Test
    public void nothingAfterStop() {
        LiveBitrate bitrate = create(40000);
        bitrate.start(true);
        bitrate.stop();

        assertFalse(bitrate.isSupported());
        feed(bitrate, loss(50f), 20000);
        bitrate.setManual(20000);
        assertTrue(host.requests.isEmpty());
    }
}
