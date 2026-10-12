package com.limelight.bitrate;

import java.util.Locale;

/**
 * Decides, once a second, whether the stream's bitrate should move: down when the network starts
 * losing frames, the round trip swells or the decoder falls behind; back up, a little at a time,
 * once things have been clean for a while. It only decides. Sending the new bitrate to the host,
 * and telling this class whether the host took it, is {@link LiveBitrate}'s job.
 *
 * <p>Ported from Moonlight V+'s local ABR controller (qiin2333/moonlight-vplus,
 * {@code AdaptiveBitrateService.kt}, GPLv3), keeping its loss tiers and probe steps:
 *
 * <pre>
 *   loss &gt; 5%                      -30% at once
 *   loss &gt; 2% for 2 samples        -10%
 *   loss &gt; 0.5% for 4 samples      -5%
 *   clean for 3 (quality) or 5 s   +5%, or +2% straight after a step down
 * </pre>
 *
 * with a cooldown between steps so the host's encoder has settled before the next sample is
 * judged. Two departures from it. The bitrate picked in the settings (or from the in-game menu) is
 * the ceiling: the controller protects that choice through a bad patch of Wi-Fi rather than
 * hunting above it. And besides loss it watches the two other numbers Ratatoskr already measures:
 * a round trip well above the stream's recent best, which is a queue building somewhere before any
 * frame is lost, and decode times longer than a frame, which no network can fix but a lighter
 * stream can. Either one, held for three samples, steps down by 10% and keeps the bitrate from
 * probing back up until it clears.
 *
 * <p>Not thread-safe: {@link LiveBitrate} drives it from one thread.
 */
public class AdaptiveBitrateController {

    /** How eagerly to trade picture for smoothness, chosen in the settings. */
    public enum Mode {
        /** Holds on to bitrate: a higher floor, and quicker to probe back up. */
        QUALITY("quality"),
        BALANCED("balanced"),
        /** Gives bitrate away readily: a low floor, a shorter cooldown and a tighter latency margin. */
        LOW_LATENCY("lowLatency");

        private final String preferenceValue;

        Mode(String preferenceValue) {
            this.preferenceValue = preferenceValue;
        }

        public String getPreferenceValue() {
            return preferenceValue;
        }

        /** The mode a stored preference names; balanced otherwise. */
        public static Mode fromPreference(String value) {
            for (Mode mode : values()) {
                if (mode.preferenceValue.equals(value)) {
                    return mode;
                }
            }
            return BALANCED;
        }
    }

    /** One second of the stream, as the decoder measured it. */
    public static final class Sample {
        /** Frames the network lost in that second, 0-100. */
        public final float lossPercent;
        /** The connection's estimated round trip, or 0 when there is no estimate yet. */
        public final int rttMs;
        /** Average time the decoder took per frame, or 0 when no frame was decoded. */
        public final float decodeTimeMs;

        public Sample(float lossPercent, int rttMs, float decodeTimeMs) {
            this.lossPercent = lossPercent;
            this.rttMs = rttMs;
            this.decodeTimeMs = decodeTimeMs;
        }
    }

    /** Takes the decoder's samples, once a second, on the decoder's thread. */
    public interface SampleListener {
        void onSample(Sample sample);
    }

    /** A bitrate to ask the host for, and why, for the log. */
    public static final class Decision {
        public final int bitrateKbps;
        public final String reason;

        Decision(int bitrateKbps, String reason) {
            this.bitrateKbps = bitrateKbps;
            this.reason = reason;
        }
    }

    static final float EMERGENCY_LOSS_PERCENT = 5f;
    static final float SUSTAINED_LOSS_PERCENT = 2f;
    static final float MILD_LOSS_PERCENT = 0.5f;

    static final double EMERGENCY_FACTOR = 0.7;
    static final double SUSTAINED_FACTOR = 0.9;
    static final double MILD_FACTOR = 0.95;
    static final double PROBE_FACTOR = 1.05;
    /** The first probe after a step down is a cautious one: the step was there for a reason. */
    static final double CAUTIOUS_PROBE_FACTOR = 1.02;

    static final int SUSTAINED_LOSS_SAMPLES = 2;
    static final int MILD_LOSS_SAMPLES = 4;
    /** Samples of swollen latency, or of a decoder slower than the frame rate, before stepping down. */
    static final int PRESSURE_SAMPLES = 3;

    /** Nothing is judged this soon after the start: the first second of a stream is never clean. */
    static final long START_GRACE_MS = 3000;

    /** The round trip's recent best is the minimum over this many samples. */
    static final int RTT_WINDOW = 15;
    /** And needs this many before latency is judged at all. */
    static final int RTT_MIN_SAMPLES = 5;

    /** A decoder taking longer than this share of a frame is overloaded. */
    static final float DECODE_OVERLOAD_FRACTION = 0.9f;
    /** Above this share there is no headroom left for a heavier stream, so no probing. */
    static final float DECODE_BUSY_FRACTION = 0.75f;

    /** Bitrates are asked for in steps of this, so the log and the menu read cleanly. */
    static final int ROUNDING_KBPS = 100;

    private final Mode mode;
    private final float frameIntervalMs;

    private int ceilingKbps;
    private int floorKbps;
    private int currentKbps;

    private int stableSamples;
    private int lossStreak;
    private int latencyStreak;
    private int decodeStreak;
    private boolean lastStepWasDown;
    /** Samples before this time are ignored: the start, or the cooldown after a change. */
    private long holdUntilMs;

    private final int[] rttWindow = new int[RTT_WINDOW];
    private int rttCount;
    private int rttNext;

    /**
     * @param ceilingKbps the bitrate the user chose, which this never goes above
     * @param currentKbps the bitrate the stream is at now
     * @param fps the stream's frame rate, which sets how long a frame may take to decode
     */
    public AdaptiveBitrateController(Mode mode, int ceilingKbps, int currentKbps, int fps, long nowMs) {
        this.mode = mode;
        this.frameIntervalMs = fps > 0 ? 1000f / fps : 0;
        setCeiling(ceilingKbps);
        this.currentKbps = clamp(currentKbps);
        this.holdUntilMs = nowMs + START_GRACE_MS;
    }

    public int getCeilingKbps() {
        return ceilingKbps;
    }

    public int getFloorKbps() {
        return floorKbps;
    }

    public int getCurrentKbps() {
        return currentKbps;
    }

    /**
     * Judge one second of the stream.
     *
     * @return the bitrate to ask the host for, or null to stay where it is
     */
    public Decision onSample(Sample sample, long nowMs) {
        if (nowMs < holdUntilMs) {
            return null;
        }

        boolean latencyHigh = isLatencyHigh(sample.rttMs);
        recordRtt(sample.rttMs);
        boolean decodeOverloaded = decodeShareAbove(sample.decodeTimeMs, DECODE_OVERLOAD_FRACTION);
        boolean decodeBusy = decodeShareAbove(sample.decodeTimeMs, DECODE_BUSY_FRACTION);

        float loss = sample.lossPercent;
        double target = currentKbps;
        String reason = null;

        if (loss > MILD_LOSS_PERCENT) {
            lossStreak++;
            stableSamples = 0;
            latencyStreak = 0;
            decodeStreak = 0;
            if (loss > EMERGENCY_LOSS_PERCENT) {
                target = currentKbps * EMERGENCY_FACTOR;
                reason = String.format(Locale.ROOT, "loss %.1f%%", loss);
            } else if (loss > SUSTAINED_LOSS_PERCENT && lossStreak >= SUSTAINED_LOSS_SAMPLES) {
                target = currentKbps * SUSTAINED_FACTOR;
                reason = String.format(Locale.ROOT, "loss %.1f%% for %ds", loss, lossStreak);
            } else if (lossStreak >= MILD_LOSS_SAMPLES) {
                target = currentKbps * MILD_FACTOR;
                reason = String.format(Locale.ROOT, "loss %.1f%% for %ds", loss, lossStreak);
            }
        } else {
            lossStreak = 0;
            latencyStreak = latencyHigh ? latencyStreak + 1 : 0;
            decodeStreak = decodeOverloaded ? decodeStreak + 1 : 0;

            if (latencyStreak >= PRESSURE_SAMPLES) {
                target = currentKbps * SUSTAINED_FACTOR;
                reason = "round trip " + sample.rttMs + " ms, best " + rttBaseline() + " ms";
                stableSamples = 0;
            } else if (decodeStreak >= PRESSURE_SAMPLES) {
                target = currentKbps * SUSTAINED_FACTOR;
                reason = String.format(Locale.ROOT, "decode %.1f ms per frame", sample.decodeTimeMs);
                stableSamples = 0;
            } else if (latencyHigh || decodeBusy) {
                // Not bad enough to step down, but no time to ask for more either.
                stableSamples = 0;
            } else {
                stableSamples++;
                if (stableSamples >= probeAfterSamples() && currentKbps < ceilingKbps) {
                    target = currentKbps * (lastStepWasDown ? CAUTIOUS_PROBE_FACTOR : PROBE_FACTOR);
                    reason = "clean for " + stableSamples + "s";
                    stableSamples = 0;
                }
            }
        }

        if (reason == null) {
            return null;
        }
        int rounded = target > currentKbps ? roundUp(target) : roundDown(target);
        int next = clamp(rounded);
        if (next == currentKbps) {
            return null;
        }
        return new Decision(next, reason);
    }

    /** The host took {@code kbps}: it is the stream's bitrate now, and the cooldown starts. */
    public void onApplied(int kbps, long nowMs) {
        lastStepWasDown = kbps < currentKbps;
        currentKbps = kbps;
        stableSamples = 0;
        latencyStreak = 0;
        decodeStreak = 0;
        holdUntilMs = nowMs + cooldownMs();
    }

    /** The host could not be told. Wait a cooldown before trying again, rather than every second. */
    public void onFailed(long nowMs) {
        holdUntilMs = nowMs + cooldownMs();
    }

    /**
     * A bitrate chosen by hand from the in-game menu. It is the new ceiling, and the stream's
     * bitrate; the counters start over, with the same grace as a new stream.
     */
    public void onManualChange(int kbps, long nowMs) {
        setCeiling(kbps);
        currentKbps = kbps;
        stableSamples = 0;
        lossStreak = 0;
        latencyStreak = 0;
        decodeStreak = 0;
        lastStepWasDown = false;
        holdUntilMs = nowMs + START_GRACE_MS;
    }

    /** The host clamped a request to its own limit: never ask for more than {@code kbps} again. */
    public void capCeiling(int kbps) {
        if (kbps < ceilingKbps) {
            setCeiling(kbps);
            currentKbps = clamp(currentKbps);
        }
    }

    private void setCeiling(int kbps) {
        ceilingKbps = Math.max(kbps, ROUNDING_KBPS);
        int floor;
        switch (mode) {
            case QUALITY:
                floor = Math.max(5000, ceilingKbps / 2);
                break;
            case LOW_LATENCY:
                floor = 2000;
                break;
            case BALANCED:
            default:
                floor = Math.max(3000, (int) (ceilingKbps * 0.3));
                break;
        }
        floorKbps = Math.min(floor, ceilingKbps);
    }

    private int clamp(int kbps) {
        return Math.max(floorKbps, Math.min(ceilingKbps, kbps));
    }

    private long cooldownMs() {
        return mode == Mode.LOW_LATENCY ? 1500 : 2000;
    }

    private int probeAfterSamples() {
        return mode == Mode.QUALITY ? 3 : 5;
    }

    private boolean decodeShareAbove(float decodeTimeMs, float fraction) {
        return frameIntervalMs > 0 && decodeTimeMs > frameIntervalMs * fraction;
    }

    /**
     * Whether a round trip is well above the recent best: more than double it and at least 15 ms
     * more, or half again and 8 ms more in low-latency mode. The best is a short window's minimum,
     * not the stream's: a lasting change of route becomes the new normal after a few steps instead
     * of pushing the bitrate down to the floor, and real congestion that outlasts the window shows
     * up as loss, which the loss tiers deal with.
     */
    private boolean isLatencyHigh(int rttMs) {
        if (rttMs <= 0 || rttCount < RTT_MIN_SAMPLES) {
            return false;
        }
        int baseline = rttBaseline();
        int margin = mode == Mode.LOW_LATENCY ? Math.max(8, baseline / 2) : Math.max(15, baseline);
        return rttMs > baseline + margin;
    }

    private void recordRtt(int rttMs) {
        if (rttMs <= 0) {
            return;
        }
        rttWindow[rttNext] = rttMs;
        rttNext = (rttNext + 1) % RTT_WINDOW;
        if (rttCount < RTT_WINDOW) {
            rttCount++;
        }
    }

    private int rttBaseline() {
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < rttCount; i++) {
            min = Math.min(min, rttWindow[i]);
        }
        return rttCount == 0 ? 0 : min;
    }

    // The slack keeps a product that is whole in decimal, like 40000 x 0.7, from landing a step
    // off because binary floating point puts it a hair below.
    private static int roundUp(double kbps) {
        return (int) Math.ceil(kbps / ROUNDING_KBPS - 1e-6) * ROUNDING_KBPS;
    }

    private static int roundDown(double kbps) {
        return (int) Math.floor(kbps / ROUNDING_KBPS + 1e-6) * ROUNDING_KBPS;
    }
}
