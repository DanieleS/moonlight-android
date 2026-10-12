package com.limelight.binding.video;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
