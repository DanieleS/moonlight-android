package com.limelight.bitrate;

import com.limelight.bitrate.AdaptiveBitrateController.Mode;
import com.limelight.bitrate.AdaptiveBitrateController.Sample;
import com.limelight.nvstream.http.HostHttpResponseException;

import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
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
        /** The host's own limit on the encoder bitrate, 0 for none. */
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
            if (failure != null) {
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
    }

    private final FakeHost host = new FakeHost();
    private final RecordingListener listener = new RecordingListener();
    private long now;

    private LiveBitrate create(int startKbps) {
        return new LiveBitrate(host, listener, Mode.BALANCED, 60, 2, startKbps,
                new InlineExecutor(), Runnable::run, () -> now);
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

        assertEquals(Collections.singletonList(BitrateSteps.toEncoderKbps(20000, 2, 40000)), host.requests);
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

        feed(bitrate, loss(8f), AdaptiveBitrateController.START_GRACE_MS);
        assertEquals(Collections.singletonList(BitrateSteps.toEncoderKbps(28000, 2, 40000)), host.requests);
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
        feed(bitrate, loss(8f), AdaptiveBitrateController.START_GRACE_MS);
        assertEquals(28000, bitrate.getCurrentKbps());

        bitrate.setAdaptive(false);
        assertFalse(bitrate.isAdaptive());
        assertEquals(40000, bitrate.getCurrentKbps());
        assertEquals(BitrateSteps.toEncoderKbps(40000, 2, 40000), (int) host.requests.get(host.requests.size() - 1));

        int sent = host.requests.size();
        feed(bitrate, loss(50f), now + 20000);
        assertEquals(sent, host.requests.size());
    }

    @Test
    public void aMissingEndpointEndsItForTheStream() {
        LiveBitrate bitrate = create(40000);
        bitrate.start(true);
        host.failure = new FileNotFoundException("/bitrate");

        feed(bitrate, loss(8f), AdaptiveBitrateController.START_GRACE_MS);
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

        feed(bitrate, loss(8f), 60000);
        assertEquals(LiveBitrate.MAX_ADAPTIVE_FAILURES, host.requests.size());
        assertFalse(bitrate.isAdaptive());
        // The host may still take a change by hand.
        assertTrue(bitrate.isSupported());
    }

    @Test
    public void aHostCapStopsProbingAboveIt() {
        LiveBitrate bitrate = create(40000);
        bitrate.start(true);
        feed(bitrate, loss(8f), AdaptiveBitrateController.START_GRACE_MS);
        assertEquals(28000, bitrate.getCurrentKbps());

        // The host won't run the encoder above what 30 Mbps works out to.
        host.maxEncoderKbps = BitrateSteps.toEncoderKbps(30000, 2, 40000);
        feed(bitrate, new Sample(0f, 4, 3f), now + 300000);
        int requestsAfterSettling = host.requests.size();
        feed(bitrate, new Sample(0f, 4, 3f), now + 300000);
        assertEquals(requestsAfterSettling, host.requests.size());
        assertTrue(bitrate.getCurrentKbps() <= 31600);
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
