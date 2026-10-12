package com.limelight.nvstream;

import com.limelight.nvstream.jni.MoonBridge;

/**
 * The rules of the opt-in auto-reconnect: which ends of a stream are worth reconnecting after,
 * how long to wait between attempts, and when to give up. Kept free of Android so it can be
 * unit-tested; Game drives the actual reconnect.
 *
 * A reconnect stops the dead connection, keeps the decoder and its last frame on screen, and
 * starts the same NvConnection again in resume-only mode, so the host's /resume brings the
 * still-running game back.
 */
public final class ReconnectPolicy {
    private ReconnectPolicy() {}

    // How long the control stream must stay quiet before it counts as a network drop. The client
    // hears from the host several times a second, so this is far above normal jitter.
    public static final int SILENCE_THRESHOLD_MS = 1500;

    // A network loss reported by Android this recently still explains a socket error.
    public static final int NETWORK_LOSS_RECENT_MS = 15000;

    // A drop this soon after a reconnect belongs to the same outage: the reconnect window keeps
    // running from the first drop, so a flapping network cannot keep the stream retrying forever.
    public static final int SAME_OUTAGE_MS = 30000;

    // Attempts back off from 1 s by 1.6x, with 20% jitter either way, up to the cap.
    static final int RETRY_BASE_MS = 1000;
    static final double RETRY_MULTIPLIER = 1.6;
    static final double RETRY_JITTER = 0.2;
    public static final int RETRY_MAX_MS = 5000;

    /**
     * Whether a stream that ended with {@code errorCode} lost its network rather than being ended
     * by the host.
     *
     * The host ending the stream (the game quit, protected content, a capture failure) is never a
     * drop. A dead ENet peer (-1) and socket errors (positive errno values) are, but only when the
     * network was seen to be in trouble just before: -1 is also what a host that disconnects on
     * purpose produces, and a positive code can also be a host termination reason passed through
     * unchanged. Anything else is not reconnected after.
     */
    public static boolean isNetworkDrop(int errorCode, boolean networkTrouble) {
        switch (errorCode) {
            case MoonBridge.ML_ERROR_GRACEFUL_TERMINATION:
            case MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION:
            case MoonBridge.ML_ERROR_PROTECTED_CONTENT:
            case MoonBridge.ML_ERROR_FRAME_CONVERSION:
                return false;
            case -1:
                return networkTrouble;
            default:
                return errorCode > 0 && networkTrouble;
        }
    }

    /**
     * Whether the network was in trouble at {@code nowMs}: the control stream has gone quiet, or
     * Android lost the network a moment ago. Times are elapsedRealtime() values, 0 meaning never.
     */
    public static boolean hadNetworkTrouble(long silenceStartMs, long networkLostAtMs, long nowMs) {
        if (silenceStartMs != 0) {
            return true;
        }
        return networkLostAtMs != 0 && nowMs - networkLostAtMs < NETWORK_LOSS_RECENT_MS;
    }

    /**
     * When the outage that a drop at {@code nowMs} belongs to began. A drop shortly after the last
     * successful reconnect continues that outage; otherwise it starts a new one now.
     */
    public static long outageStartMs(long nowMs, long lastReconnectedAtMs, long lastOutageStartMs) {
        if (lastReconnectedAtMs != 0 && lastOutageStartMs != 0 && nowMs - lastReconnectedAtMs < SAME_OUTAGE_MS) {
            return lastOutageStartMs;
        }
        return nowMs;
    }

    /** Whether reconnecting may go on {@code elapsedMs} into an outage, given the user's window. */
    public static boolean withinWindow(long elapsedMs, int windowSeconds) {
        return elapsedMs < windowSeconds * 1000L;
    }

    /**
     * The wait before attempt {@code attempt} (1-based): 1 s growing by 1.6x per attempt with
     * 20% jitter, never longer than {@link #RETRY_MAX_MS}. {@code random01} is uniform in [0, 1),
     * passed in so the rule stays testable. Android reporting a network starts an attempt at once
     * instead.
     */
    public static long retryDelayMs(int attempt, double random01) {
        double base = RETRY_BASE_MS * Math.pow(RETRY_MULTIPLIER, Math.max(0, attempt - 1));
        double jittered = base * (1 - RETRY_JITTER + 2 * RETRY_JITTER * random01);
        return Math.min(RETRY_MAX_MS, Math.round(jittered));
    }

    /**
     * Whether a failed attempt means the session cannot come back, so trying again is pointless:
     * the game is no longer running on the host, it belongs to another client (470), it is
     * minimized (525), or the host refuses us (401, 403). Anything else (no answer, a timeout, a
     * host error) is tried again until the window runs out.
     */
    public static boolean isAttemptFailureFinal(int errorCode) {
        switch (errorCode) {
            case NvConnection.RESUME_ERROR_NO_SESSION:
            case 401:
            case 403:
            case 470:
            case 525:
                return true;
            default:
                return false;
        }
    }
}
