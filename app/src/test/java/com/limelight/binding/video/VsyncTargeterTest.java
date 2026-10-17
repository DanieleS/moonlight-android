package com.limelight.binding.video;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VsyncTargeterTest {
    private static final long P = 8_333_333L; // 120 Hz

    private static VsyncTargeter learned() {
        VsyncTargeter t = new VsyncTargeter();
        for (int i = 0; i < 20; i++) {
            t.noteVsync(1_000_000_000L + i * P);
        }
        return t;
    }

    @Test
    public void returnsFallbackUntilPeriodObserved() {
        VsyncTargeter t = new VsyncTargeter();
        assertEquals(123L, t.targetFor(123L, 50L));
        t.noteVsync(1_000_000_000L);
        assertEquals(123L, t.targetFor(123L, 50L));
    }

    @Test
    public void learnsPeriodAndIgnoresMissedCallbacks() {
        VsyncTargeter t = learned();
        assertEquals(P, t.getObservedPeriodNs());
        t.noteVsync(1_000_000_000L + 19 * P + 60_000_000L); // a stall, not a period
        assertEquals(P, t.getObservedPeriodNs());
    }

    @Test
    public void targetsFirstLatchableVsync() {
        VsyncTargeter t = learned();
        long last = 1_000_000_000L + 19 * P;
        // 1 ms after the last vsync: the next one is more than the margin away
        long now = last + 1_000_000L;
        long target = t.targetFor(now, now);
        assertEquals(last + P, target);
        // Inside the margin of the next vsync: skip to the one after
        now = last + P - 1_000_000L;
        assertEquals(last + 2 * P, t.targetFor(last + P, now));
    }

    @Test
    public void neverPushesMoreThanOnePeriodPastFallback() {
        VsyncTargeter t = learned();
        long last = 1_000_000_000L + 19 * P;
        long now = last + P - 1_000_000L;
        assertEquals(now - P, t.targetFor(now - P, now));
    }

    @Test
    public void marginFollowsMeasuredOvershootWithinBounds() {
        VsyncTargeter t = learned();
        assertEquals(VsyncTargeter.DEFAULT_MARGIN_NS, t.currentMarginNs());
        t.noteTarget(7, 5_000_000L);
        t.noteRendered(7, 6_000_000L);
        assertEquals(1_000_000L + VsyncTargeter.MARGIN_FLOOR_NS, t.currentMarginNs());

        VsyncTargeter big = learned();
        big.noteTarget(1, 5_000_000L);
        big.noteRendered(1, 5_000_000L + 40_000_000L);
        assertTrue(big.currentMarginNs() <= P / 2);
    }

    @Test
    public void rejectsStaleAndImplausibleSamples() {
        VsyncTargeter t = learned();
        t.noteTarget(1, 5_000_000L);
        t.noteTarget(1 + 32, 6_000_000L); // same slot, overwrites
        t.noteRendered(1, 9_000_000L);
        assertEquals(VsyncTargeter.DEFAULT_MARGIN_NS, t.currentMarginNs());

        t.noteTarget(2, 5_000_000L);
        t.noteRendered(2, 5_000_000L + 80_000_000L);
        assertEquals(VsyncTargeter.DEFAULT_MARGIN_NS, t.currentMarginNs());
    }
}
