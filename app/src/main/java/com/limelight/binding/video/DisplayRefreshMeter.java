package com.limelight.binding.video;

import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Display;

import com.limelight.LimeLog;

import java.util.Locale;

/**
 * Measures the display's actual refresh rate from vsync times.
 *
 * Android reports the nominal rate of the display mode (120 Hz), while the panel may run a
 * little off it (119.88 Hz). The host paces capture and encoding to clientRefreshRateX100 when
 * we send one, so sending the nominal rate makes it drift against our vsyncs: a repeated or
 * skipped frame every few seconds at a 0.1 Hz difference. Half a second of vsync timestamps
 * gives the rate to well under 0.001 Hz.
 *
 * Runs on the main thread. The display mode may still be switching when the stream starts: the
 * meter waits for the mode Game asked for, and a measurement that doesn't fit one steady rate is
 * retried.
 */
public class DisplayRefreshMeter implements Choreographer.FrameCallback {
    public interface Listener {
        // Measured refresh rate, or 0 if it couldn't be measured
        void onMeasured(double refreshHz);
    }

    private static final long MEASURE_NS = 500_000_000L;
    // Frames right after starting can be delivered late while the main thread is busy
    private static final int SKIP_FRAMES = 5;
    private static final int MAX_ATTEMPTS = 3;
    private static final long TIMEOUT_MS = 2000;
    // Enough for 0.5 s at 240 Hz, with room to spare
    private static final int MAX_SAMPLES = 256;

    private final Display display;
    private final float expectedHz;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long[] frameTimes = new long[MAX_SAMPLES];
    private int frameCount;
    private float nominalHz;
    private int skipped;
    private int attempts;
    private boolean waitingForMode;
    private boolean done;

    private DisplayRefreshMeter(Display display, float expectedHz, Listener listener) {
        this.display = display;
        this.expectedHz = expectedHz;
        this.listener = listener;
    }

    /**
     * Starts measuring on the main thread, once the display reports expectedHz (the refresh rate
     * the stream asked for; 0 for whatever it runs at). The listener is called there exactly
     * once, unless cancelled.
     */
    public static DisplayRefreshMeter measure(Display display, float expectedHz, Listener listener) {
        DisplayRefreshMeter meter = new DisplayRefreshMeter(display, expectedHz, listener);
        meter.startAttempt();
        meter.handler.postDelayed(() -> {
            if (!meter.done) {
                if (meter.waitingForMode) {
                    LimeLog.warning(String.format(Locale.ROOT, "Display stayed at %.3f Hz instead of %.3f Hz; refresh rate not measured",
                            meter.display.getRefreshRate(), expectedHz));
                }
                else {
                    LimeLog.warning("Display refresh measurement timed out");
                }
                meter.finish(0);
            }
        }, TIMEOUT_MS);
        return meter;
    }

    /**
     * Whether a display reporting currentHz is in the mode that was asked for. The mode switch
     * can start after the stream's surface is up, and measuring the mode it is leaving would
     * hand the host the old rate.
     */
    public static boolean isExpectedMode(float currentHz, float expectedHz) {
        return !(expectedHz > 0) || Math.abs(currentHz - expectedHz) <= expectedHz * 0.01;
    }

    // Stops the measurement without calling the listener
    public void cancel() {
        done = true;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacksAndMessages(null);
    }

    /**
     * The clientRefreshRateX100 to send for a stream of streamFps on a display refreshing at
     * refreshHz, or 0 to send none.
     *
     * Vibepollo captures and encodes at exactly this rate, and ignores it unless it rounds to
     * the requested FPS. So it is the refresh rate divided by the whole number of vsyncs each
     * frame is shown for (119.88 for 120 FPS at 119.88 Hz, 59.94 for 60 FPS), and nothing when
     * the stream rate doesn't divide the refresh rate (60 FPS at 144 Hz, or the 119 FPS of
     * "Balanced with FPS limit"), since no host rate lines up with our vsyncs then.
     *
     * Rounded down: a host a hair slower than the display costs a repeated frame now and then,
     * one a hair faster builds up a queued frame of latency.
     */
    public static int clientRefreshRateX100(double refreshHz, int streamFps) {
        if (!(refreshHz > 0) || streamFps <= 0) {
            return 0;
        }
        long vsyncsPerFrame = Math.round(refreshHz / streamFps);
        if (vsyncsPerFrame < 1) {
            return 0;
        }
        double rate = refreshHz / vsyncsPerFrame;
        if (Math.round(rate) != streamFps || Math.abs(rate - streamFps) > streamFps * 0.02) {
            return 0;
        }
        // The small allowance keeps a float's representation error (59.94f is 59.93999...) from
        // dropping a whole hundredth
        return (int) Math.floor(rate * 100 + 0.005);
    }

    /**
     * Least squares fit of frame time against vsync index, for count vsync timestamps. Returns
     * the refresh rate, or 0 if the times don't fit one steady rate within 1% of nominalHz (the
     * mode changed while measuring). Frames the main thread missed leave gaps of whole vsyncs,
     * which the fit allows for.
     */
    public static double fitRefreshHz(long[] frameTimesNs, int count, double nominalHz) {
        if (!(nominalHz > 0) || count < 2) {
            return 0;
        }
        final double nominalPeriod = 1e9 / nominalHz;
        final long first = frameTimesNs[0];
        long[] index = new long[count];
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < count; i++) {
            double y = frameTimesNs[i] - first;
            index[i] = Math.round(y / nominalPeriod);
            sx += index[i];
            sy += y;
            sxx += (double) index[i] * index[i];
            sxy += index[i] * y;
        }
        double denominator = count * sxx - sx * sx;
        if (denominator <= 0) {
            return 0;
        }
        double period = (count * sxy - sx * sy) / denominator;
        if (!(period > 0)) {
            return 0;
        }
        double intercept = (sy - period * sx) / count;
        for (int i = 0; i < count; i++) {
            double residual = (frameTimesNs[i] - first) - (intercept + period * index[i]);
            if (Math.abs(residual) > period / 4) {
                return 0;
            }
        }
        double hz = 1e9 / period;
        return Math.abs(hz - nominalHz) <= nominalHz * 0.01 ? hz : 0;
    }

    private void startAttempt() {
        attempts++;
        frameCount = 0;
        skipped = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (done) {
            return;
        }
        float currentHz = display.getRefreshRate();
        if (!isExpectedMode(currentHz, expectedHz)) {
            // Not there yet. Wait, within the timeout, without using up an attempt.
            waitingForMode = true;
            frameCount = 0;
            skipped = 0;
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }
        waitingForMode = false;
        if (skipped == 0 && frameCount == 0) {
            nominalHz = currentHz;
        }
        if (skipped < SKIP_FRAMES) {
            skipped++;
        }
        else if (frameCount < MAX_SAMPLES) {
            frameTimes[frameCount++] = frameTimeNanos;
        }

        if (frameCount < MAX_SAMPLES &&
                (frameCount < 2 || frameTimes[frameCount - 1] - frameTimes[0] < MEASURE_NS)) {
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }

        // A mode switch during the attempt shows up as a changed nominal rate too
        double hz = Math.abs(display.getRefreshRate() - nominalHz) > nominalHz * 0.01 ?
                0 : fitRefreshHz(frameTimes, frameCount, nominalHz);
        if (hz > 0) {
            finish(hz);
        }
        else if (attempts < MAX_ATTEMPTS) {
            startAttempt();
        }
        else {
            LimeLog.warning("Display refresh rate didn't settle; not measured");
            finish(0);
        }
    }

    private void finish(double hz) {
        if (done) {
            return;
        }
        cancel();
        if (hz > 0) {
            LimeLog.info(String.format(Locale.ROOT, "Measured display refresh rate: %.4f Hz (nominal %.3f Hz)", hz, nominalHz));
        }
        listener.onMeasured(hz);
    }
}
