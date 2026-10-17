package com.limelight.utils;

import android.content.Context;

import com.limelight.R;

/**
 * Turns the frame loss the client already measures into something a user can read and act on:
 * the loss percentage and a suggested bitrate, shown once at session end.
 *
 * <p>The loss percentage is measured. The suggested bitrate is arithmetic on a measurement: the
 * requested rate scaled by the delivered fraction, with a margin taken off, rounded down to
 * 5 Mbps. It does not claim to know why frames were lost (a saturated link and a host that
 * cannot keep up look the same from here).
 */
public final class LinkHealthSummary {

    /** Moonlight's own connection-status logic treats 5% as okay, so this sits just above. */
    private static final double NOTABLE_LOSS_PERCENT = 8.0;

    private static final double SEVERE_LOSS_PERCENT = 25.0;

    /** Startup dominates shorter sessions, so their loss number would mean nothing. */
    private static final long MIN_MEANINGFUL_SESSION_MS = 20_000L;

    /** Headroom left below the delivered rate when suggesting a bitrate. */
    private static final double SUGGESTION_HEADROOM = 0.8;

    private final int framesReceived;
    private final int framesLost;
    private final int requestedBitrateKbps;
    private final long sessionDurationMs;

    public LinkHealthSummary(int framesReceived, int framesLost,
                             int requestedBitrateKbps, long sessionDurationMs) {
        this.framesReceived = Math.max(0, framesReceived);
        this.framesLost = Math.max(0, framesLost);
        this.requestedBitrateKbps = requestedBitrateKbps;
        this.sessionDurationMs = sessionDurationMs;
    }

    /** Frames the host produced, as far as the client can tell from frame numbers. */
    public int getFramesProduced() {
        return framesReceived + framesLost;
    }

    public double getLossPercent() {
        int produced = getFramesProduced();
        if (produced <= 0) {
            return 0;
        }
        return (framesLost * 100.0) / produced;
    }

    /** Bitrate to suggest in kbps, or 0 when there is nothing useful to say. */
    public int getSuggestedBitrateKbps() {
        if (!isWorthReporting() || requestedBitrateKbps <= 0) {
            return 0;
        }

        double deliveredFraction = 1.0 - (getLossPercent() / 100.0);
        int suggestion = (int) (requestedBitrateKbps * deliveredFraction * SUGGESTION_HEADROOM);
        suggestion = (suggestion / 5000) * 5000;
        if (suggestion < 5000) {
            suggestion = 5000;
        }
        // Never suggest going up
        if (suggestion >= requestedBitrateKbps) {
            return 0;
        }
        return suggestion;
    }

    public boolean isSevere() {
        return getLossPercent() >= SEVERE_LOSS_PERCENT;
    }

    public boolean isWorthReporting() {
        return sessionDurationMs >= MIN_MEANINGFUL_SESSION_MS
                && getFramesProduced() > 0
                && getLossPercent() >= NOTABLE_LOSS_PERCENT;
    }

    /** User-facing message, or null when there is nothing worth saying. */
    public String buildMessage(Context context) {
        if (!isWorthReporting()) {
            return null;
        }

        String headline = context.getString(
                isSevere() ? R.string.link_health_severe : R.string.link_health_notable,
                Math.round(getLossPercent()));

        int suggestion = getSuggestedBitrateKbps();
        if (suggestion <= 0) {
            return headline;
        }

        return headline + "\n" + context.getString(R.string.link_health_suggestion,
                requestedBitrateKbps / 1000, suggestion / 1000);
    }

    /** Compact form for the log. */
    public String toDiagnosticString() {
        return "frames_produced=" + getFramesProduced()
                + " frames_received=" + framesReceived
                + " frames_lost=" + framesLost
                + " loss_pct=" + String.format(java.util.Locale.US, "%.1f", getLossPercent())
                + " requested_kbps=" + requestedBitrateKbps
                + " suggested_kbps=" + getSuggestedBitrateKbps();
    }
}
