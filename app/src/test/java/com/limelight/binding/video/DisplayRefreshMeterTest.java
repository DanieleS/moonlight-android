package com.limelight.binding.video;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;

public class DisplayRefreshMeterTest {
    // Vsync timestamps at hz for durationNs, with jitter of up to jitterNs and every skipEvery-th
    // vsync missed (0 for none), as the main thread's Choreographer delivers them.
    private static int vsyncs(long[] out, double hz, long durationNs, long jitterNs, int skipEvery, long seed) {
        Random random = new Random(seed);
        double period = 1e9 / hz;
        long start = 123_456_789_000L;
        int count = 0;
        for (int i = 0; i * period <= durationNs && count < out.length; i++) {
            if (skipEvery > 0 && i > 0 && i % skipEvery == 0) {
                continue;
            }
            long jitter = jitterNs == 0 ? 0 : (long) ((random.nextDouble() * 2 - 1) * jitterNs);
            out[count++] = start + Math.round(i * period) + jitter;
        }
        return count;
    }

    @Test
    public void fitsThePanelRateRatherThanTheNominalOne() {
        long[] times = new long[256];
        int n = vsyncs(times, 119.88, 500_000_000L, 0, 0, 1);
        assertEquals(119.88, DisplayRefreshMeter.fitRefreshHz(times, n, 120f), 0.0001);
    }

    @Test
    public void toleratesJitterAndMissedVsyncs() {
        // Choreographer's frame times are vsync timestamps, so their jitter is microseconds even
        // when the callbacks themselves run late or are missed
        long[] times = new long[256];
        int n = vsyncs(times, 119.97, 500_000_000L, 20_000L, 7, 2);
        assertEquals(119.97, DisplayRefreshMeter.fitRefreshHz(times, n, 120f), 0.005);
    }

    @Test
    public void rejectsARateThatChangedWhileMeasuring() {
        // 90 Hz for a quarter second, then 120 Hz: the mode switch was still in progress
        long[] times = new long[256];
        int n = vsyncs(times, 90, 250_000_000L, 0, 0, 3);
        long last = times[n - 1];
        for (int i = 1; i <= 30; i++) {
            times[n++] = last + Math.round(i * 1e9 / 120);
        }
        assertEquals(0, DisplayRefreshMeter.fitRefreshHz(times, n, 120f), 0);
        assertEquals(0, DisplayRefreshMeter.fitRefreshHz(times, n, 90f), 0);

        // 60 then 120 Hz, against the 60 Hz nominal rate read when the attempt started. (Against
        // 120 it would pass as 120 Hz with every other vsync missed; the meter also rejects an
        // attempt whose nominal rate changed by its end.)
        n = vsyncs(times, 60, 250_000_000L, 0, 0, 4);
        last = times[n - 1];
        for (int i = 1; i <= 30; i++) {
            times[n++] = last + Math.round(i * 1e9 / 120);
        }
        assertEquals(0, DisplayRefreshMeter.fitRefreshHz(times, n, 60f), 0);
    }

    @Test
    public void rejectsARateOffTheNominalOne() {
        long[] times = new long[256];
        int n = vsyncs(times, 121.5, 500_000_000L, 0, 0, 5);
        assertEquals(0, DisplayRefreshMeter.fitRefreshHz(times, n, 120f), 0);
        n = vsyncs(times, 90, 500_000_000L, 0, 0, 6);
        assertEquals(0, DisplayRefreshMeter.fitRefreshHz(times, n, 120f), 0);
    }

    @Test
    public void rejectsTooFewSamples() {
        assertEquals(0, DisplayRefreshMeter.fitRefreshHz(new long[] {0}, 1, 120f), 0);
        assertEquals(0, DisplayRefreshMeter.fitRefreshHz(new long[] {0, 8_333_333L}, 2, 0f), 0);
    }

    @Test
    public void asksForTheRefreshRateWhenTheStreamMatchesIt() {
        assertEquals(11988, DisplayRefreshMeter.clientRefreshRateX100(119.88, 120));
        assertEquals(12000, DisplayRefreshMeter.clientRefreshRateX100(120.0, 120));
        assertEquals(6000, DisplayRefreshMeter.clientRefreshRateX100(60.0, 60));
    }

    @Test
    public void dividesByTheVsyncsEachFrameIsShownFor() {
        assertEquals(5994, DisplayRefreshMeter.clientRefreshRateX100(119.88, 60));
        assertEquals(3000, DisplayRefreshMeter.clientRefreshRateX100(120.0, 30));
        assertEquals(7182, DisplayRefreshMeter.clientRefreshRateX100(143.65, 72));
    }

    @Test
    public void roundsDownSoTheHostIsNeverFasterThanThePanel() {
        assertEquals(11998, DisplayRefreshMeter.clientRefreshRateX100(119.9899, 120));
        assertEquals(11999, DisplayRefreshMeter.clientRefreshRateX100(119.9999, 120));
        // ...but a float's representation error doesn't cost a hundredth
        assertEquals(5994, DisplayRefreshMeter.clientRefreshRateX100(59.94f, 60));
        assertEquals(14400, DisplayRefreshMeter.clientRefreshRateX100(144f, 144));
    }

    @Test
    public void sendsNothingWhenNoHostRateLinesUp() {
        // 60 FPS at 144 Hz alternates 2 and 3 vsyncs per frame
        assertEquals(0, DisplayRefreshMeter.clientRefreshRateX100(144.0, 60));
        // "Balanced with FPS limit" asks for one below the refresh rate on purpose
        assertEquals(0, DisplayRefreshMeter.clientRefreshRateX100(119.88, 119));
        // Warp Drive asks for a multiple of the refresh rate
        assertEquals(0, DisplayRefreshMeter.clientRefreshRateX100(120.0, 240));
        assertEquals(0, DisplayRefreshMeter.clientRefreshRateX100(120.0, 480));
        // The panel never reached the stream's rate
        assertEquals(0, DisplayRefreshMeter.clientRefreshRateX100(60.0, 120));
    }

    @Test
    public void sendsNothingWithoutARateOrFps() {
        assertEquals(0, DisplayRefreshMeter.clientRefreshRateX100(0, 120));
        assertEquals(0, DisplayRefreshMeter.clientRefreshRateX100(Double.NaN, 120));
        assertEquals(0, DisplayRefreshMeter.clientRefreshRateX100(120.0, 0));
    }

    @Test
    public void whatItSendsPassesTheHostsCheck() {
        // Vibepollo drops clientRefreshRateX100 unless lround(x100 / 100.0) equals maxFPS
        double[] rates = {59.94, 60.0, 89.91, 90.0, 119.88, 119.99, 120.0, 143.65, 144.0, 165.0};
        int[] fpsValues = {30, 45, 60, 72, 90, 120, 144, 165};
        for (double hz : rates) {
            for (int fps : fpsValues) {
                int x100 = DisplayRefreshMeter.clientRefreshRateX100(hz, fps);
                if (x100 > 0) {
                    assertEquals(hz + " Hz, " + fps + " FPS", fps, Math.round(x100 / 100.0));
                }
            }
        }
    }
}
