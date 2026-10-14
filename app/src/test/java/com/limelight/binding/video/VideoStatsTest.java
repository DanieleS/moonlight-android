package com.limelight.binding.video;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VideoStatsTest {
    @Test
    public void receiveTimeCountsMillisecondBoundariesCrossed() {
        assertEquals(0, VideoStats.receiveTimeMs(1_000_000, 1_000_000));
        assertEquals(0, VideoStats.receiveTimeMs(1_000_100, 1_000_900));
        assertEquals(1, VideoStats.receiveTimeMs(1_000_900, 1_001_100));
        assertEquals(3, VideoStats.receiveTimeMs(1_000_000, 1_003_200));
    }

    @Test
    public void receiveTimeSumIsNotBiasedLow() {
        // 1000 frames that each took 1.7 ms to arrive, starting at every microsecond offset
        // within a millisecond. Truncating each 1700 us delta would report 1000 ms.
        VideoStats stats = new VideoStats();
        for (int offsetUs = 0; offsetUs < 1000; offsetUs++) {
            long receiveTimeUs = 5_000_000 + offsetUs;
            stats.totalTimeMs += VideoStats.receiveTimeMs(receiveTimeUs, receiveTimeUs + 1_700);
        }
        assertEquals(1700, stats.totalTimeMs);
    }

    @Test
    public void freshTimestampIsOnMonotonicClock() {
        long nowUs = 3_600_000_000L;
        assertTrue(VideoStats.isOnMonotonicClock(nowUs, nowUs));
        assertTrue(VideoStats.isOnMonotonicClock(nowUs - 40_000, nowUs));
        assertTrue(VideoStats.isOnMonotonicClock(nowUs + 500, nowUs));
    }

    @Test
    public void unrebasedTimestampIsNotOnMonotonicClock() {
        // LiGetMicroseconds() counts from its first call, so an unrebased timestamp is close
        // to zero while CLOCK_MONOTONIC is the device uptime.
        long nowUs = 3_600_000_000L;
        assertFalse(VideoStats.isOnMonotonicClock(2_500_000, nowUs));
        assertFalse(VideoStats.isOnMonotonicClock(nowUs - 1_000_000, nowUs));
    }
}
