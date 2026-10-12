package com.limelight.bitrate;

import com.limelight.bitrate.AdaptiveBitrateController.Decision;
import com.limelight.bitrate.AdaptiveBitrateController.Mode;
import com.limelight.bitrate.AdaptiveBitrateController.Sample;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AdaptiveBitrateControllerTest {
    private static final Sample CLEAN = new Sample(0f, 4, 3f);

    /** A controller past its start grace, at {@code t}. */
    private long t;

    private AdaptiveBitrateController balanced(int ceilingKbps) {
        t = 0;
        AdaptiveBitrateController c = new AdaptiveBitrateController(Mode.BALANCED, ceilingKbps, ceilingKbps, 60, t);
        t += AdaptiveBitrateController.START_GRACE_MS;
        return c;
    }

    private Decision next(AdaptiveBitrateController c, Sample s) {
        t += 1000;
        return c.onSample(s, t);
    }

    /** Feed a sample, and when it decides, tell it the host took the new bitrate. */
    private Decision step(AdaptiveBitrateController c, Sample s) {
        Decision d = next(c, s);
        if (d != null) {
            c.onApplied(d.bitrateKbps, t);
        }
        return d;
    }

    private static Sample loss(float percent) {
        return new Sample(percent, 4, 3f);
    }

    private static Sample rtt(int ms) {
        return new Sample(0f, ms, 3f);
    }

    @Test
    public void nothingIsJudgedDuringTheStartGrace() {
        AdaptiveBitrateController c = new AdaptiveBitrateController(Mode.BALANCED, 40000, 40000, 60, 0);
        assertNull(c.onSample(loss(50f), 1000));
        assertNull(c.onSample(loss(50f), 2999));
        assertNotNull(c.onSample(loss(50f), 3000));
    }

    @Test
    public void heavyLossCutsByThirtyPercentAtOnce() {
        AdaptiveBitrateController c = balanced(40000);
        Decision d = next(c, loss(8f));
        assertEquals(28000, d.bitrateKbps);
    }

    @Test
    public void moderateLossNeedsTwoSamples() {
        AdaptiveBitrateController c = balanced(40000);
        assertNull(next(c, loss(3f)));
        assertEquals(36000, next(c, loss(3f)).bitrateKbps);
    }

    @Test
    public void mildLossNeedsFourSamples() {
        AdaptiveBitrateController c = balanced(40000);
        assertNull(next(c, loss(1f)));
        assertNull(next(c, loss(1f)));
        assertNull(next(c, loss(1f)));
        assertEquals(38000, next(c, loss(1f)).bitrateKbps);
    }

    @Test
    public void aCleanSampleBreaksALossStreak() {
        AdaptiveBitrateController c = balanced(40000);
        assertNull(next(c, loss(3f)));
        assertNull(next(c, CLEAN));
        assertNull(next(c, loss(3f)));
    }

    @Test
    public void lossBelowHalfAPercentIsClean() {
        AdaptiveBitrateController c = balanced(40000);
        for (int i = 0; i < 20; i++) {
            assertNull(next(c, loss(0.4f)));
        }
    }

    @Test
    public void cooldownHoldsTheNextStep() {
        AdaptiveBitrateController c = balanced(40000);
        assertEquals(28000, step(c, loss(8f)).bitrateKbps);
        // Applied at t; the next sample, one second on, is still inside the two-second cooldown.
        assertNull(next(c, loss(8f)));
        assertEquals(19600, next(c, loss(8f)).bitrateKbps);
    }

    @Test
    public void afterAFailedRequestItWaitsACooldown() {
        AdaptiveBitrateController c = balanced(40000);
        assertNotNull(next(c, loss(8f)));
        c.onFailed(t);
        assertNull(next(c, loss(8f)));
        assertEquals(28000, next(c, loss(8f)).bitrateKbps);
    }

    @Test
    public void neverGoesBelowTheFloor() {
        AdaptiveBitrateController c = balanced(40000);
        assertEquals(12000, c.getFloorKbps());
        for (int i = 0; i < 30; i++) {
            step(c, loss(20f));
        }
        assertEquals(12000, c.getCurrentKbps());
        // At the floor there is nowhere left to go.
        t += 10000;
        assertNull(next(c, loss(20f)));
    }

    @Test
    public void probesBackUpCautiouslyAfterAStepDown() {
        AdaptiveBitrateController c = balanced(40000);
        assertEquals(28000, step(c, loss(8f)).bitrateKbps);
        // The first sample falls in the cooldown; four clean ones follow it.
        for (int i = 0; i < 5; i++) {
            assertNull(step(c, CLEAN));
        }
        // The fifth clean one probes: +2% right after a step down, rounded up to 100 kbps.
        assertEquals(28600, step(c, CLEAN).bitrateKbps);
        for (int i = 0; i < 5; i++) {
            assertNull(step(c, CLEAN));
        }
        // Then +5% once the last step was up.
        assertEquals(30100, step(c, CLEAN).bitrateKbps);
    }

    @Test
    public void neverProbesAboveTheChosenBitrate() {
        AdaptiveBitrateController c = balanced(40000);
        for (int i = 0; i < 60; i++) {
            assertNull(step(c, CLEAN));
        }
        assertEquals(40000, c.getCurrentKbps());

        AdaptiveBitrateController d = balanced(40000);
        step(d, loss(3f));
        step(d, loss(3f));
        assertEquals(36000, d.getCurrentKbps());
        for (int i = 0; i < 200; i++) {
            step(d, CLEAN);
            assertTrue(d.getCurrentKbps() <= 40000);
        }
        assertEquals(40000, d.getCurrentKbps());
    }

    @Test
    public void qualityModeHoldsMoreAndProbesSooner() {
        t = 0;
        AdaptiveBitrateController c = new AdaptiveBitrateController(Mode.QUALITY, 40000, 30000, 60, t);
        t += AdaptiveBitrateController.START_GRACE_MS;
        assertEquals(20000, c.getFloorKbps());
        assertNull(step(c, CLEAN));
        assertNull(step(c, CLEAN));
        assertEquals(31500, step(c, CLEAN).bitrateKbps);
    }

    @Test
    public void lowLatencyModeHasALowFloor() {
        AdaptiveBitrateController c = new AdaptiveBitrateController(Mode.LOW_LATENCY, 40000, 40000, 60, 0);
        assertEquals(2000, c.getFloorKbps());
    }

    @Test
    public void floorNeverExceedsALowCeiling() {
        AdaptiveBitrateController c = new AdaptiveBitrateController(Mode.QUALITY, 3000, 3000, 60, 0);
        assertEquals(3000, c.getFloorKbps());
        assertEquals(3000, c.getCeilingKbps());
    }

    @Test
    public void swollenRoundTripStepsDownAfterThreeSamples() {
        AdaptiveBitrateController c = balanced(40000);
        for (int i = 0; i < 10; i++) {
            assertNull(step(c, rtt(4)));
        }
        // Best is 4 ms: more than 4 + max(15, 4) = 19 ms is swollen.
        assertNull(step(c, rtt(30)));
        assertNull(step(c, rtt(30)));
        assertEquals(36000, step(c, rtt(30)).bitrateKbps);
    }

    @Test
    public void aModestRiseInRoundTripIsNotPressure() {
        AdaptiveBitrateController c = balanced(40000);
        for (int i = 0; i < 10; i++) {
            step(c, rtt(4));
        }
        for (int i = 0; i < 10; i++) {
            assertNull(step(c, rtt(15)));
        }
    }

    @Test
    public void aSwollenRoundTripBlocksProbing() {
        AdaptiveBitrateController c = balanced(40000);
        step(c, loss(3f));
        step(c, loss(3f));
        assertEquals(36000, c.getCurrentKbps());
        for (int i = 0; i < 10; i++) {
            step(c, rtt(4));
        }
        int before = c.getCurrentKbps();
        // Alternating so the streak never reaches three: no step down, and no probe up either.
        for (int i = 0; i < 20; i++) {
            assertNull(step(c, i % 2 == 0 ? rtt(40) : rtt(25)));
            if (i % 2 == 1) {
                assertNull(step(c, rtt(4)));
            }
        }
        assertEquals(before, c.getCurrentKbps());
    }

    @Test
    public void aLastingRouteChangeBecomesTheNewNormal() {
        AdaptiveBitrateController c = balanced(40000);
        for (int i = 0; i < 10; i++) {
            step(c, rtt(4));
        }
        // Steady at 40 ms: a few steps down at first, then the window forgets the 4 ms.
        for (int i = 0; i < AdaptiveBitrateController.RTT_WINDOW * 2 + 5; i++) {
            step(c, rtt(40));
        }
        int settled = c.getCurrentKbps();
        assertTrue(settled > c.getFloorKbps());
        for (int i = 0; i < 4; i++) {
            Decision d = step(c, rtt(40));
            assertTrue(d == null || d.bitrateKbps > settled);
        }
    }

    @Test
    public void aDecoderSlowerThanTheFrameRateStepsDown() {
        AdaptiveBitrateController c = balanced(40000);
        // 60 fps: 16.7 ms a frame, overloaded above 15 ms.
        Sample slow = new Sample(0f, 4, 16f);
        assertNull(step(c, slow));
        assertNull(step(c, slow));
        assertEquals(36000, step(c, slow).bitrateKbps);
    }

    @Test
    public void aBusyDecoderBlocksProbing() {
        AdaptiveBitrateController c = balanced(40000);
        step(c, loss(3f));
        step(c, loss(3f));
        int before = c.getCurrentKbps();
        // 13 ms of 16.7: past three quarters, short of overloaded.
        Sample busy = new Sample(0f, 4, 13f);
        for (int i = 0; i < 20; i++) {
            assertNull(step(c, busy));
        }
        assertEquals(before, c.getCurrentKbps());
    }

    @Test
    public void unknownRoundTripAndDecodeTimeAreIgnored() {
        AdaptiveBitrateController c = new AdaptiveBitrateController(Mode.BALANCED, 40000, 40000, 0, 0);
        t = AdaptiveBitrateController.START_GRACE_MS;
        for (int i = 0; i < 10; i++) {
            assertNull(step(c, new Sample(0f, 0, 500f)));
        }
    }

    @Test
    public void aManualChangeIsTheNewCeilingAndStartsOver() {
        AdaptiveBitrateController c = balanced(40000);
        step(c, loss(3f));
        assertNull(next(c, CLEAN));
        c.onManualChange(20000, t);
        assertEquals(20000, c.getCeilingKbps());
        assertEquals(20000, c.getCurrentKbps());
        assertEquals(6000, c.getFloorKbps());
        // A new grace, and the old loss streak is forgotten.
        assertNull(c.onSample(loss(8f), t + 1000));
        assertNull(next(c, CLEAN));
        t += AdaptiveBitrateController.START_GRACE_MS;
        assertNull(next(c, loss(3f)));
        for (int i = 0; i < 20; i++) {
            step(c, CLEAN);
        }
        assertEquals(20000, c.getCurrentKbps());
    }

    @Test
    public void aHostCapLowersTheCeiling() {
        AdaptiveBitrateController c = balanced(40000);
        c.capCeiling(30000);
        assertEquals(30000, c.getCeilingKbps());
        assertEquals(30000, c.getCurrentKbps());
        c.capCeiling(50000);
        assertEquals(30000, c.getCeilingKbps());
    }

    @Test
    public void modeFromPreference() {
        assertEquals(Mode.QUALITY, Mode.fromPreference("quality"));
        assertEquals(Mode.LOW_LATENCY, Mode.fromPreference("lowLatency"));
        assertEquals(Mode.BALANCED, Mode.fromPreference("balanced"));
        assertEquals(Mode.BALANCED, Mode.fromPreference("nonsense"));
        assertEquals(Mode.BALANCED, Mode.fromPreference(null));
    }
}
