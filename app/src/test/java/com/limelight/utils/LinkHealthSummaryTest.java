package com.limelight.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LinkHealthSummaryTest {
    private static final long LONG_SESSION = 60_000L;

    @Test
    public void quietBelowThresholdOrOnShortSessions() {
        assertFalse(new LinkHealthSummary(950, 50, 50000, LONG_SESSION).isWorthReporting());
        assertFalse(new LinkHealthSummary(500, 500, 50000, 5_000L).isWorthReporting());
        assertFalse(new LinkHealthSummary(0, 0, 50000, LONG_SESSION).isWorthReporting());
    }

    @Test
    public void suggestsScaledRoundedBitrate() {
        // Half the frames lost at 50 Mbps: 50000 * 0.5 * 0.8 = 20000
        LinkHealthSummary s = new LinkHealthSummary(500, 500, 50000, LONG_SESSION);
        assertTrue(s.isWorthReporting());
        assertTrue(s.isSevere());
        assertEquals(20000, s.getSuggestedBitrateKbps());
        // 10% lost at 33000: 33000 * 0.9 * 0.8 = 23760 -> 20000
        assertEquals(20000, new LinkHealthSummary(900, 100, 33000, LONG_SESSION).getSuggestedBitrateKbps());
    }

    @Test
    public void neverSuggestsGoingUpOrBelowFloor() {
        assertEquals(5000, new LinkHealthSummary(100, 900, 20000, LONG_SESSION).getSuggestedBitrateKbps());
        assertEquals(0, new LinkHealthSummary(900, 100, 5000, LONG_SESSION).getSuggestedBitrateKbps());
    }
}
