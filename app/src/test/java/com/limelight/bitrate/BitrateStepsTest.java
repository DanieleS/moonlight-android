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
        assertEquals(38988, BitrateSteps.toEncoderKbps(50000, 2, 50000));
        // 10 Mbps stereo: 8000, normal audio 192 -> 7808, overhead 500 -> 7308.
        assertEquals(7308, BitrateSteps.toEncoderKbps(10000, 2, 10000));
        // 5.1 at 80 Mbps: 64000, 6 x 256 = 1536 -> 62464, -500 -> 61964.
        assertEquals(61964, BitrateSteps.toEncoderKbps(80000, 6, 80000));
        // 1 Mbps: the caps bite. 800, audio capped at 160 -> 640, overhead capped at 64 -> 576.
        assertEquals(576, BitrateSteps.toEncoderKbps(1000, 2, 1000));
    }

    @Test
    public void audioQualityFollowsTheBitrateTheStreamStartedWith() {
        // Started at 50 Mbps, so the audio stays high quality when the controller drops to 10.
        assertEquals(10000 * 4 / 5 - 512 - 500, BitrateSteps.toEncoderKbps(10000, 2, 50000));
        // Started below 15 Mbps, so it stays normal quality above it.
        assertEquals(20000 * 4 / 5 - 192 - 500, BitrateSteps.toEncoderKbps(20000, 2, 10000));
    }

    @Test
    public void lowerBudgetMeansLowerEncoderBitrate() {
        int previous = 0;
        for (int kbps = 500; kbps <= 300000; kbps += 500) {
            int encoder = BitrateSteps.toEncoderKbps(kbps, 2, 40000);
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
