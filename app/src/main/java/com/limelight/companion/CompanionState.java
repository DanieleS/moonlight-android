package com.limelight.companion;

import android.graphics.Bitmap;

import com.limelight.binding.video.PerfStats;
import com.limelight.nvstream.http.AppMetadata;

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

    /** The game currently under the spotlight while browsing the library, if any. */
    private String browsingTitle;
    /** Metadata for {@link #browsingTitle}, or null when the host provided none for it. */
    private AppMetadata browsingMetadata;
    /** Background/hero art for {@link #browsingTitle}; arrives after the metadata, async. */
    private Bitmap browsingBackground;

    private CompanionState() {
    }

    public static CompanionState getInstance() {
        return INSTANCE;
    }

    /** The surface registers while it is on screen. At most one draws at a time. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Deregister, but only if {@code who} is still the one registered. While the surface is
     * being re-hosted the incoming panel registers before the outgoing one tears down, so the
     * outgoing panel must not clear a listener that is no longer its own.
     */
    public void clearListener(Listener who) {
        if (listener == who) {
            listener = null;
        }
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

    /**
     * Point the idle companion surface at the game currently centred in the library. Metadata may
     * be null: the title alone is still worth showing.
     */
    public void setBrowsing(String title, AppMetadata metadata) {
        this.browsingTitle = title;
        this.browsingMetadata = metadata;
        // The background belongs to the previous game; drop it until the new one's arrives.
        this.browsingBackground = null;
        notifyChanged();
    }

    /** Attach the background art once it has loaded for the current spotlight. */
    public void setBrowsingBackground(Bitmap background) {
        this.browsingBackground = background;
        notifyChanged();
    }

    /** Stop spotlighting a game (e.g. on leaving the library). */
    public void clearBrowsing() {
        if (browsingTitle == null && browsingMetadata == null && browsingBackground == null) {
            return;
        }
        browsingTitle = null;
        browsingMetadata = null;
        browsingBackground = null;
        notifyChanged();
    }

    public String getBrowsingTitle() {
        return browsingTitle;
    }

    public AppMetadata getBrowsingMetadata() {
        return browsingMetadata;
    }

    public Bitmap getBrowsingBackground() {
        return browsingBackground;
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onCompanionStateChanged();
        }
    }
}
