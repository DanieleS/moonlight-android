package com.limelight.companion;

import android.graphics.Bitmap;

import com.limelight.LimeLog;
import com.limelight.binding.video.PerfStats;
import com.limelight.nvstream.http.AppMetadata;

/**
 * The companion screen's state, as two independent regions.
 *
 * There is one companion panel, so there is one state, and it outlives any single
 * Presentation: re-hosting the surface on another Activity does not lose the current stats.
 *
 * Producers push into it and the surface redraws; nothing polls. Main thread only.
 *
 * <h3>Why two regions rather than one enum</h3>
 *
 * "What is there to show" and "who owns the screen" answer different questions and change on
 * different events, so they are kept apart. A companion app taking the screen while a stream runs
 * does not stop the stream, and when the app closes the panel must come back to the stats — not to
 * whatever the library was last spotlighting. Folding the two into a single enum forces every
 * content event to be repeated once per owner, and invites exactly the class of bug this replaces:
 * inferring the mode from whether a payload happens to be null.
 *
 * <pre>
 * ┌─ content ────────────────────────────────────────────────┐
 * │   IDLE ──enterBrowsing──▶ BROWSING ──leaveBrowsing──▶ IDLE│
 * │   IDLE ──enterStreaming─▶ STREAMING ─leaveStreaming─▶ IDLE│
 * └──────────────────────────────────────────────────────────┘
 * ┌─ owner ──────────────────────────────────────────────────┐
 * │   PANEL ◀──▶ OFF            (the user's own show/hide)    │
 * │   PANEL ◀──▶ COMPANION_APP  (an app borrows the screen)   │
 * └──────────────────────────────────────────────────────────┘
 * </pre>
 *
 * The panel is on screen exactly when {@code owner == PANEL}; what it draws depends only on
 * {@code content}. Nothing is inferred from the payloads.
 */
public class CompanionState {

    /** What there is to show. Driven by whichever screen is in the foreground. */
    public enum Content { IDLE, BROWSING, STREAMING }

    /** Who the secondary screen belongs to right now. */
    public enum Owner {
        /** Our panel is up. */
        PANEL,
        /** A companion app was launched onto the screen and is holding it. */
        COMPANION_APP,
        /** The user hid the panel by hand; remembered until they ask for it back. */
        OFF
    }

    public interface Listener {
        void onCompanionStateChanged();
    }

    /** Told when the owner region moves, so the panel can be attached or torn down. */
    public interface OwnerListener {
        void onCompanionOwnerChanged();
    }

    private static final CompanionState INSTANCE = new CompanionState();

    private Content content = Content.IDLE;
    private Owner owner = Owner.PANEL;

    /** Null when not streaming, or streaming with the stats switched off. */
    private PerfStats stats;
    private Listener listener;
    private OwnerListener ownerListener;

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

    // --- The content region ---

    public Content getContent() {
        return content;
    }

    /** The library went away. */
    public void leaveBrowsing() {
        if (content == Content.BROWSING) {
            setContent(Content.IDLE);
        }
    }

    /** A stream connected. */
    public void enterStreaming() {
        setContent(Content.STREAMING);
    }

    /** The stream ended; its stats and telemetry go with it. */
    public void leaveStreaming() {
        if (content == Content.STREAMING) {
            stats = null;
            // Both belong to the game that just ended. Left behind, the panel would keep drawing a
            // view fed by a stream that has stopped — the last frame of a dead game, forever.
            telemetryView = null;
            telemetryStream = null;
            setContent(Content.IDLE);
        }
    }

    private void setContent(Content next) {
        if (content == next) {
            return;
        }
        // Browsing and streaming never overlap: the library is stopped while a game runs. Landing
        // here means a producer pushed from a screen that should have been gone, which is worth
        // hearing about rather than silently drawing the wrong surface.
        if (content == Content.STREAMING && next == Content.BROWSING) {
            LimeLog.warning("CompanionState: refusing BROWSING while STREAMING");
            return;
        }
        content = next;
        notifyChanged();
    }

    public void setStats(PerfStats stats) {
        this.stats = stats;
        notifyChanged();
    }

    /**
     * Drop the numbers without leaving the stream: the user switched the stats off, so the panel
     * falls back to a bare surface rather than sitting on a frozen snapshot. It must not fall back
     * to the library spotlight — that game is not the one being played.
     */
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

    // --- Telemetry ---
    //
    // Only the two facts that change rarely live here: whether this game has a view, and the stream
    // to follow. The frames themselves do not — they arrive up to twenty times a second, and pushing
    // each one through notifyChanged() would redraw the whole surface to deliver a number. A frame
    // goes straight into the view that is already on screen, the way a video frame does.

    /** The HTML view for the running game, or null when it has none installed. */
    private String telemetryView;
    private TelemetryStream telemetryStream;

    /**
     * Give the panel a view to draw for the running game. Null means this game has none, and the
     * panel falls back to the stats.
     */
    public void setTelemetryView(String html) {
        if (telemetryView == null ? html == null : telemetryView.equals(html)) {
            return;
        }
        telemetryView = html;
        notifyChanged();
    }

    public String getTelemetryView() {
        return telemetryView;
    }

    /**
     * The stream the panel should follow. Held here because the panel is created and destroyed as
     * the surface moves between activities, while the stream outlives any one of them.
     */
    public void setTelemetryStream(TelemetryStream stream) {
        telemetryStream = stream;
    }

    public TelemetryStream getTelemetryStream() {
        return telemetryStream;
    }

    // --- The owner region ---

    public Owner getOwner() {
        return owner;
    }

    /** Whether our panel is the thing on the secondary screen right now. */
    public boolean isPanelOwner() {
        return owner == Owner.PANEL;
    }

    public void setOwner(Owner next) {
        if (owner == next) {
            return;
        }
        owner = next;
        if (ownerListener != null) {
            ownerListener.onCompanionOwnerChanged();
        }
    }

    /**
     * Registered by the display manager, which attaches and tears down the panel. Kept apart from
     * {@link Listener} because that one belongs to whichever surface is currently drawing, and is
     * swapped mid-flight while the panel is re-hosted.
     */
    public void setOwnerListener(OwnerListener ownerListener) {
        this.ownerListener = ownerListener;
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
        // Spotlighting a game is what "browsing" means; no separate call to keep in step with it.
        setContent(Content.BROWSING);
        notifyChanged();
    }

    /** Attach the background art once it has loaded for the current spotlight. */
    public void setBrowsingBackground(Bitmap background) {
        this.browsingBackground = background;
        notifyChanged();
    }

    /** Stop spotlighting a game, and leave the browsing surface with it. */
    public void clearBrowsing() {
        leaveBrowsing();
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
