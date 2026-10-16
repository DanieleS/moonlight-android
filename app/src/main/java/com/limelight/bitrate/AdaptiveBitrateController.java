package com.limelight.bitrate;

import java.util.Locale;

/**
 * Decides, once a second, whether the stream's bitrate should move: down when the network starts
 * losing frames, the round trip swells or the decoder falls behind; back up, a little at a time,
 * once things have been clean for a while. It only decides. Sending the new bitrate to the host,
 * and telling this class whether the host took it, is {@link LiveBitrate}'s job.
 *
 * <p>Ported from Moonlight V+'s local ABR controller (qiin2333/moonlight-vplus,
 * {@code AdaptiveBitrateService.kt}, GPLv3), keeping its loss tiers:
 *
 * <pre>
 *   loss &gt; 5% over the last 2 s    -30% at once
 *   loss &gt; 2% for 2 samples        -10%
 *   loss &gt; 0.5% for 4 samples      -5%
 *   clean for 6 (quality) or 10 s  +10%, at least 2 Mbps; 5 s more straight after a step down
 * </pre>
 *
 * with a cooldown between steps so the host's encoder has settled before the next sample is
 * judged. The heavy-loss tier looks at two seconds, as V+ does through its overlay's window: loss
 * is counted in whole frames, every frame dropped while waiting for a recovery frame among them,
 * so a single second would take one short burst for a collapse.
 *
 * <p>Three departures from it. The bitrate picked in the settings (or from the in-game menu) is
 * the ceiling: the controller protects that choice through a bad patch of Wi-Fi rather than
 * hunting above it. Steps up are fewer and larger than V+'s +2%/+5% every few seconds, because on
 * Vibepollo with NVENC every raise resets the rate control and forces a key frame, a burst of
 * traffic that is itself the kind of thing that loses frames on Wi-Fi. And besides loss it
 * watches the two other numbers Zefiro already measures. A round trip well above the stream's
 * recent best, a queue building somewhere before any frame is lost, held for three samples, steps
 * down by 10% and keeps the bitrate from probing up until it clears. The queue is also measured
 * directly, from each frame's arrival against the host's capture time ({@link Sample#queueDelayMs}):
 * two frames of it standing, or a rise of 3 ms a second, count the same as a swollen round trip. Decode times longer than a
 * frame are only a maybe: how long a hardware decoder takes depends far more on resolution and
 * frame rate than on bitrate. So three such samples buy one 10% step, and the decode time after it
 * decides the rest of the stream. If it fell, decode time keeps counting, both to step down and to
 * hold off probing while the decoder is busy; if it didn't, decode time is ignored from then on.
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

        /**
         * The queue at the access point, from frame arrival against the host's capture time:
         * the mean one-way delay above its recent minimum, or -1 when unknown.
         */
        public final float queueDelayMs;

        public Sample(float lossPercent, int rttMs, float decodeTimeMs) {
            this(lossPercent, rttMs, decodeTimeMs, -1);
        }

        public Sample(float lossPercent, int rttMs, float decodeTimeMs, float queueDelayMs) {
            this.lossPercent = lossPercent;
            this.rttMs = rttMs;
            this.decodeTimeMs = decodeTimeMs;
            this.queueDelayMs = queueDelayMs;
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
    static final double PROBE_FACTOR = 1.1;
    /** A step up costs the host a key frame, so it is never a small one. */
    static final int MIN_PROBE_STEP_KBPS = 2000;
    /** Clean samples needed before a step up: quality mode, and the others. */
    static final int QUALITY_PROBE_SAMPLES = 6;
    static final int PROBE_SAMPLES = 10;
    /** And this many more straight after a step down: the step was there for a reason. */
    static final int CAUTIOUS_EXTRA_SAMPLES = 5;

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

    /** A queue of this many frames (and at least {@link #QUEUE_BUILDING_MIN_MS}) is congestion. */
    static final float QUEUE_STANDING_FRAMES = 2f;
    static final float QUEUE_BUILDING_MIN_MS = 20f;
    /** A queue this far above the best one-way delay, and rising this much a second, is building. */
    static final float QUEUE_RISING_MS = 3f;
    /** A queue of at least this share of a frame (and 4 ms) keeps the bitrate from probing up. */
    static final float QUEUE_BUSY_FRAMES = 0.5f;

    /** A decoder taking longer than this share of a frame is overloaded. */
    static final float DECODE_OVERLOAD_FRACTION = 0.9f;
    /** Above this share there is no headroom left for a heavier stream, so no probing. */
    static final float DECODE_BUSY_FRACTION = 0.75f;
    /**
     * How much a decode-driven step must lower the decode time to show that the bitrate is what
     * the decoder was struggling with.
     */
    static final float DECODE_RELIEF_FRACTION = 0.1f;

    /** What a step down has shown about decode time on this stream. */
    enum DecodeVerdict {
        /** No decode-driven step tried yet: an overloaded decoder may buy one. */
        UNTRIED,
        /** A step brought decode time down: it counts, to step down and to hold off probing. */
        FOLLOWS_BITRATE,
        /** A step didn't: decode time is ignored for the rest of the stream. */
        INDEPENDENT
    }

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
    private float decodeStreakSumMs;
    private boolean lastStepWasDown;
    /** Samples before this time are ignored: the start, or the cooldown after a change. */
    private long holdUntilMs;
    /** Whether that hold is a start grace, whose samples aren't even remembered as the last one. */
    private boolean holdIsGrace;
    /** The loss in the sample before this one, for the two-second view of heavy loss. */
    private float previousLossPercent;
    /** The queue delay in the sample before this one, to see whether it is rising; -1 if unknown. */
    private float previousQueueMs = -1;

    private DecodeVerdict decodeVerdict = DecodeVerdict.UNTRIED;
    /** The last decision returned was a decode-driven step, so its outcome is worth checking. */
    private boolean decidedForDecode;
    /** Average decode time over the streak that led to the step being checked. */
    private float decodeBeforeStepMs;
    /** Collecting the decode times after a decode-driven step, to see whether it helped. */
    private boolean decodeCheckPending;
    private float decodeCheckSumMs;
    private int decodeCheckCount;

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
        this.holdIsGrace = true;
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

    DecodeVerdict getDecodeVerdict() {
        return decodeVerdict;
    }

    /**
     * Judge one second of the stream.
     *
     * @return the bitrate to ask the host for, or null to stay where it is
     */
    public Decision onSample(Sample sample, long nowMs) {
        float previousLoss = previousLossPercent;
        float previousQueue = previousQueueMs;
        previousQueueMs = sample.queueDelayMs;
        if (nowMs < holdUntilMs) {
            // The cooldown's samples are the stream after the change, and make a fair first half
            // of the next two seconds; the start's are not.
            previousLossPercent = holdIsGrace ? 0 : sample.lossPercent;
            return null;
        }
        previousLossPercent = sample.lossPercent;

        boolean rttHigh = isLatencyHigh(sample.rttMs);
        boolean queueBuilding = isQueueBuilding(sample.queueDelayMs, previousQueue);
        boolean latencyHigh = rttHigh || queueBuilding;
        boolean queueBusy = sample.queueDelayMs >= Math.max(4f, frameIntervalMs * QUEUE_BUSY_FRAMES);
        recordRtt(sample.rttMs);
        checkDecodeStep(sample.decodeTimeMs);
        boolean decodeCounts = decodeVerdict != DecodeVerdict.INDEPENDENT && !decodeCheckPending;
        boolean decodeOverloaded = decodeCounts
                && decodeShareAbove(sample.decodeTimeMs, DECODE_OVERLOAD_FRACTION);
        boolean decodeBusy = decodeVerdict == DecodeVerdict.FOLLOWS_BITRATE
                && decodeShareAbove(sample.decodeTimeMs, DECODE_BUSY_FRACTION);

        float loss = sample.lossPercent;
        float twoSecondLoss = (previousLoss + loss) / 2;
        double target = currentKbps;
        String reason = null;
        boolean forDecode = false;

        if (loss > MILD_LOSS_PERCENT) {
            lossStreak++;
            stableSamples = 0;
            latencyStreak = 0;
            decodeStreak = 0;
            decodeStreakSumMs = 0;
            if (twoSecondLoss > EMERGENCY_LOSS_PERCENT) {
                target = currentKbps * EMERGENCY_FACTOR;
                reason = String.format(Locale.ROOT, "loss %.1f%% over 2s", twoSecondLoss);
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
            if (decodeOverloaded) {
                decodeStreak++;
                decodeStreakSumMs += sample.decodeTimeMs;
            } else {
                decodeStreak = 0;
                decodeStreakSumMs = 0;
            }

            if (latencyStreak >= PRESSURE_SAMPLES) {
                target = currentKbps * SUSTAINED_FACTOR;
                reason = rttHigh
                        ? "round trip " + sample.rttMs + " ms, best " + rttBaseline() + " ms"
                        : String.format(Locale.ROOT, "queue %.1f ms", sample.queueDelayMs);
                stableSamples = 0;
            } else if (decodeStreak >= PRESSURE_SAMPLES) {
                target = currentKbps * SUSTAINED_FACTOR;
                reason = String.format(Locale.ROOT, "decode %.1f ms per frame", sample.decodeTimeMs);
                forDecode = true;
                decodeBeforeStepMs = decodeStreakSumMs / decodeStreak;
                stableSamples = 0;
            } else if (latencyHigh || queueBusy || decodeBusy) {
                // Not bad enough to step down, but no time to ask for more either.
                stableSamples = 0;
            } else {
                stableSamples++;
                if (stableSamples >= probeAfterSamples() && currentKbps < ceilingKbps) {
                    target = Math.max(currentKbps * PROBE_FACTOR, currentKbps + MIN_PROBE_STEP_KBPS);
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
        decidedForDecode = forDecode;
        return new Decision(next, reason);
    }

    /** The host took {@code kbps}: it is the stream's bitrate now, and the cooldown starts. */
    public void onApplied(int kbps, long nowMs) {
        lastStepWasDown = kbps < currentKbps;
        // A decode-driven step down is checked once the cooldown is over; any other change
        // muddles that check, so it is dropped and the verdict stays as it was.
        decodeCheckPending = decidedForDecode && lastStepWasDown;
        decodeCheckSumMs = 0;
        decodeCheckCount = 0;
        decidedForDecode = false;
        currentKbps = kbps;
        stableSamples = 0;
        latencyStreak = 0;
        decodeStreak = 0;
        decodeStreakSumMs = 0;
        holdUntilMs = nowMs + cooldownMs();
        holdIsGrace = false;
    }

    /** The host could not be told. Wait a cooldown before trying again, rather than every second. */
    public void onFailed(long nowMs) {
        decidedForDecode = false;
        holdUntilMs = nowMs + cooldownMs();
        holdIsGrace = false;
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
        decodeStreakSumMs = 0;
        decodeCheckPending = false;
        decidedForDecode = false;
        lastStepWasDown = false;
        previousLossPercent = 0;
        previousQueueMs = -1;
        holdUntilMs = nowMs + START_GRACE_MS;
        holdIsGrace = true;
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
        int samples = mode == Mode.QUALITY ? QUALITY_PROBE_SAMPLES : PROBE_SAMPLES;
        return lastStepWasDown ? samples + CAUTIOUS_EXTRA_SAMPLES : samples;
    }

    /**
     * After a decode-driven step down, average the next few decode times and judge whether the
     * step made the decoder's work lighter. One verdict either way settles it: a decoder that
     * takes as long at a lower bitrate is limited by something no bitrate changes.
     */
    private void checkDecodeStep(float decodeTimeMs) {
        if (!decodeCheckPending || decodeTimeMs <= 0) {
            return;
        }
        decodeCheckSumMs += decodeTimeMs;
        if (++decodeCheckCount < PRESSURE_SAMPLES) {
            return;
        }
        float after = decodeCheckSumMs / decodeCheckCount;
        decodeVerdict = after <= decodeBeforeStepMs * (1 - DECODE_RELIEF_FRACTION)
                ? DecodeVerdict.FOLLOWS_BITRATE : DecodeVerdict.INDEPENDENT;
        decodeCheckPending = false;
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

    /**
     * Whether the queue at the access point is congestion: standing at two frames (and 20 ms),
     * or at three-quarters of a frame and rising by 3 ms a second. It shows before any frame is
     * lost, and is counted with the round trip's swelling, so three samples step down.
     */
    private boolean isQueueBuilding(float queueMs, float previousQueueMs) {
        if (queueMs < 0) {
            return false;
        }
        if (queueMs >= Math.max(QUEUE_BUILDING_MIN_MS, frameIntervalMs * QUEUE_STANDING_FRAMES)) {
            return true;
        }
        return previousQueueMs >= 0 && queueMs >= Math.max(8f, frameIntervalMs * 0.75f)
                && queueMs - previousQueueMs >= QUEUE_RISING_MS;
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
