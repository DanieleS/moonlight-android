package com.limelight.bitrate;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BitrateStepsTest {

    // Worked through rtsp.cpp's sum by hand: /1.25 for 20% FEC, less the audio (capped at a
    // fifth), less 500 kbps (capped at a tenth).
    @Test
    public void encoderBitrateMatchesWhatTheHostStartsAStreamWith() {
        // 50 Mbps stereo: 40000, high-quality audio 512 -> 39488, overhead 500 -> 38988.
        assertEquals(38988, BitrateSteps.toEncoderKbps(50000, 2, 50000, 0));
        // 10 Mbps stereo: 8000, normal audio 192 -> 7808, overhead 500 -> 7308.
        assertEquals(7308, BitrateSteps.toEncoderKbps(10000, 2, 10000, 0));
        // 5.1 at 80 Mbps: 64000, 6 x 256 = 1536 -> 62464, -500 -> 61964.
        assertEquals(61964, BitrateSteps.toEncoderKbps(80000, 6, 80000, 0));
        // 1 Mbps: the caps bite. 800, audio capped at 160 -> 640, overhead capped at 64 -> 576.
        assertEquals(576, BitrateSteps.toEncoderKbps(1000, 2, 1000, 0));
    }

    @Test
    public void audioQualityFollowsTheBitrateTheStreamStartedWith() {
        // Started at 50 Mbps, so the audio stays high quality when the controller drops to 10.
        assertEquals(10000 * 4 / 5 - 512 - 500, BitrateSteps.toEncoderKbps(10000, 2, 50000, 0));
        // Started below 15 Mbps, so it stays normal quality above it.
        assertEquals(20000 * 4 / 5 - 192 - 500, BitrateSteps.toEncoderKbps(20000, 2, 10000, 0));
    }

    @Test
    public void warpMultipliesTheBudgetFirst() {
        // rtsp.cpp multiplies by the warp factor before any deduction.
        assertEquals(BitrateSteps.toEncoderKbps(100000, 2, 50000, 0), BitrateSteps.toEncoderKbps(50000, 2, 50000, 2));
        assertEquals(BitrateSteps.toEncoderKbps(200000, 2, 50000, 0), BitrateSteps.toEncoderKbps(50000, 2, 50000, 4));
        assertEquals(BitrateSteps.toEncoderKbps(50000, 2, 50000, 0), BitrateSteps.toEncoderKbps(50000, 2, 50000, 1));
    }

    @Test
    public void pyroWaveKeepsNoFecShareButPaysForAudioAndOverheadInFull() {
        // rtsp.cpp for a PyroWave session: no FEC share, audio with its 50% parity
        // (512 * 3 / 2 = 768) and 500 kbps of overhead, neither capped.
        assertEquals(400000 - 768 - 500,
                BitrateSteps.toEncoderKbps(400000, 2, 400000, 0, BitrateSteps.HostBudget.PYROWAVE));
        // Warp still multiplies first.
        assertEquals(800000 - 768 - 500,
                BitrateSteps.toEncoderKbps(400000, 2, 400000, 2, BitrateSteps.HostBudget.PYROWAVE));
        // Never below 1.
        assertEquals(1, BitrateSteps.toEncoderKbps(1000, 2, 400000, 0, BitrateSteps.HostBudget.PYROWAVE));
    }

    @Test
    public void dynamicFecLeavesParityToTheHost() {
        // With dynamic FEC the host takes no FEC share off the top: 50000, audio 512 -> 49488,
        // overhead 500 -> 48988, which is the video budget it then splits with parity itself.
        assertEquals(48988,
                BitrateSteps.toEncoderKbps(50000, 2, 50000, 0, BitrateSteps.HostBudget.DYNAMIC_FEC));
        // The overhead cap still bites at low bitrates: 1000, normal audio 192 -> 808, overhead
        // capped at a tenth (80) -> 728.
        assertEquals(728, BitrateSteps.toEncoderKbps(1000, 2, 1000, 0, BitrateSteps.HostBudget.DYNAMIC_FEC));
    }

    @Test
    public void fixedFecIsTheDefault() {
        for (int kbps = 1000; kbps <= 150000; kbps += 7000) {
            assertEquals(BitrateSteps.toEncoderKbps(kbps, 2, 40000, 2),
                    BitrateSteps.toEncoderKbps(kbps, 2, 40000, 2, BitrateSteps.HostBudget.FIXED_FEC));
        }
    }

    @Test
    public void lowerBudgetMeansLowerEncoderBitrate() {
        int previous = 0;
        for (int kbps = 500; kbps <= 300000; kbps += 500) {
            int encoder = BitrateSteps.toEncoderKbps(kbps, 2, 40000, 0);
            assertTrue(encoder > previous);
            assertTrue(encoder < kbps);
            previous = encoder;
        }
    }

    @Test
    public void menuChoicesIncludeTheChosenAndCurrentBitrates() {
        int[] choices = BitrateSteps.menuChoices(45000, 38700);
        assertArrayEquals(new int[]{
                5000, 10000, 15000, 20000, 30000, 38700, 40000, 45000, 50000, 60000, 80000, 100000, 150000
        }, choices);
    }

    @Test
    public void menuChoicesDoNotRepeat() {
        assertArrayEquals(BitrateSteps.LADDER_KBPS, BitrateSteps.menuChoices(40000, 40000));
        assertArrayEquals(BitrateSteps.LADDER_KBPS, BitrateSteps.menuChoices(0, 0));
    }

    @Test
    public void formatMbps() {
        assertEquals("40", BitrateSteps.formatMbps(40000, Locale.US));
        assertEquals("38.7", BitrateSteps.formatMbps(38700, Locale.US));
        assertEquals("38,7", BitrateSteps.formatMbps(38700, Locale.ITALY));
        assertEquals("0.5", BitrateSteps.formatMbps(500, Locale.US));
    }
}
