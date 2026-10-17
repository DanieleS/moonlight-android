package com.limelight.binding.video;

/**
 * Picks the display vsync a decoded frame should be released for.
 *
 * <p>The cadence is learned from Choreographer callbacks, and how early a buffer must be
 * released to be latched for a vsync is measured: MediaCodec.OnFrameRenderedListener reports
 * renderTimeNanos on the System.nanoTime() clock, the same epoch as the target asked for, so
 * (renderTimeNanos - targetNs) is the error between where we aimed and where the frame landed.
 *
 * <p>Render to scan-out is not measured (the decoder writes straight into the surface), which
 * is why the margin keeps a floor. The margin is bounded at both ends so a bad measurement
 * cannot turn into a latency regression. Until a period has been observed the original target
 * is returned unchanged.
 *
 * <p>Targets are stored direct-mapped by PTS, with the PTS alongside, so there is no lock on
 * the present path and a collision or stale entry costs one dropped sample, never a wrong one.
 */
final class VsyncTargeter {
    /** Margin used until an overshoot has been measured. */
    static final long DEFAULT_MARGIN_NS = 2_000_000L;
    /** Covers the render-to-scan-out hop we cannot see. */
    static final long MARGIN_FLOOR_NS = 500_000L;
    static final long MARGIN_CEILING_NS = 6_000_000L;
    /** Overshoot samples beyond this either way are mispairings or stalls. */
    static final long MAX_PLAUSIBLE_OVERSHOOT_NS = 50_000_000L;

    private static final int SLOTS = 32;

    // Written on the display thread, read on the renderer thread. They are only used together
    // to compute a target that is then clamped, so a torn pair gives one slightly wrong target.
    private volatile long observedVsyncNs;
    private volatile long observedPeriodNs;

    private final long[] targetPtsUs = new long[SLOTS];
    private final long[] targetNs = new long[SLOTS];

    private volatile long overshootEwmaNs;
    private volatile boolean overshootValid;

    /** Call from the Choreographer callback with its frame time. */
    void noteVsync(long frameTimeNanos) {
        long prev = observedVsyncNs;
        if (prev > 0) {
            long delta = frameTimeNanos - prev;
            // Between 240 Hz and 24 Hz. A missed callback yields a multiple of the period.
            if (delta > 4_000_000L && delta < 41_000_000L) {
                long cur = observedPeriodNs;
                observedPeriodNs = cur <= 0 ? delta : (cur * 7 + delta) / 8;
            }
        }
        observedVsyncNs = frameTimeNanos;
    }

    long getObservedPeriodNs() {
        return observedPeriodNs;
    }

    long currentMarginNs() {
        if (!overshootValid) {
            return DEFAULT_MARGIN_NS;
        }
        long margin = Math.max(overshootEwmaNs + MARGIN_FLOOR_NS, MARGIN_FLOOR_NS);
        long period = observedPeriodNs;
        long ceiling = period > 0 ? Math.min(MARGIN_CEILING_NS, period / 2) : MARGIN_CEILING_NS;
        return Math.min(margin, ceiling);
    }

    /**
     * The first vsync boundary far enough ahead to be latchable, never more than one period
     * beyond {@code fallbackNs}. Returns {@code fallbackNs} until a period has been observed.
     */
    long targetFor(long fallbackNs, long nowNs) {
        long period = observedPeriodNs;
        long last = observedVsyncNs;
        if (period <= 0 || last <= 0) {
            return fallbackNs;
        }

        long earliest = nowNs + currentMarginNs();
        if (earliest <= last) {
            return last;
        }
        long steps = (earliest - last + period - 1) / period;
        long target = last + steps * period;
        if (fallbackNs > 0 && target > fallbackNs + period) {
            return fallbackNs;
        }
        return target;
    }

    /** Remember where a frame was aimed; call before the release. */
    void noteTarget(long presentationTimeUs, long target) {
        int slot = (int) Math.floorMod(presentationTimeUs, (long) SLOTS);
        targetPtsUs[slot] = presentationTimeUs;
        targetNs[slot] = target;
    }

    /** Fold in where a frame actually landed. Codec callback thread. */
    void noteRendered(long presentationTimeUs, long renderTimeNanos) {
        int slot = (int) Math.floorMod(presentationTimeUs, (long) SLOTS);
        if (targetPtsUs[slot] != presentationTimeUs) {
            return;
        }
        long target = targetNs[slot];
        if (target <= 0) {
            return;
        }
        targetNs[slot] = 0;

        long overshoot = renderTimeNanos - target;
        if (overshoot < -MAX_PLAUSIBLE_OVERSHOOT_NS || overshoot > MAX_PLAUSIBLE_OVERSHOOT_NS) {
            return;
        }
        long cur = overshootEwmaNs;
        // Slow EWMA: this tracks a device constant, it should converge and stay put.
        overshootEwmaNs = overshootValid ? (cur * 15 + overshoot) / 16 : overshoot;
        overshootValid = true;
    }
}
