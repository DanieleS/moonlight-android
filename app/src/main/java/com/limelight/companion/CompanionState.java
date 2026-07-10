package com.limelight.companion;

import com.limelight.binding.video.PerfStats;

/**
 * What the companion surface draws.
 *
 * There is one companion panel, so there is one state, and it outlives any single
 * Presentation: re-hosting the surface on another Activity does not lose the current stats.
 *
 * Producers push into it and the surface redraws; nothing polls. Main thread only.
 */
public class CompanionState {

    public interface Listener {
        void onCompanionStateChanged();
    }

    private static final CompanionState INSTANCE = new CompanionState();

    /** Null whenever we are not streaming, or streaming with the stats turned off. */
    private PerfStats stats;
    private Listener listener;

    private CompanionState() {
    }

    public static CompanionState getInstance() {
        return INSTANCE;
    }

    /** The surface registers while it is on screen. At most one exists at a time. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setStats(PerfStats stats) {
        this.stats = stats;
        notifyChanged();
    }

    /** Back to the idle surface. Called when the stream ends or the stats are switched off. */
    public void clearStats() {
        if (stats == null) {
            return;
        }
        stats = null;
        notifyChanged();
    }

    public PerfStats getStats() {
        return stats;
    }

    public boolean isStreaming() {
        return stats != null;
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onCompanionStateChanged();
        }
    }
}
