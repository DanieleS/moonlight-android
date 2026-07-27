package com.limelight.companion;

import android.content.Context;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONObject;

/**
 * Ties a stream session's telemetry together: follows the host's stream, works out which view draws
 * it, and hands both to {@link CompanionState} for the panel to pick up.
 *
 * <h3>Why the view is resolved from a frame rather than at startup</h3>
 *
 * Which view to draw depends on the <em>profile</em> scry chose, and that choice is made against the
 * running game's memory on the host — nothing here knows it in advance, and the app name is no
 * substitute. So the view is resolved from the first snapshot, which is the moment the profile
 * becomes known. Until then the panel shows the performance stats, which is the correct thing to
 * show for a game with no view at all.
 */
public class TelemetryController implements TelemetryStream.Listener {

    private final CompanionState state;
    private final TelemetryViewStore views;
    private final TelemetryStream stream;

    /** The profile a view was last resolved for, so an unchanged profile is not re-resolved. */
    private String resolvedProfile;

    public TelemetryController(Context context, NvHTTP http, CompanionState state) {
        this.state = state;
        this.views = new TelemetryViewStore.LocalFolder(context);
        this.stream = new TelemetryStream(http);
    }

    public void start() {
        state.setTelemetryStream(stream);
        stream.addListener(this);
        stream.start();
    }

    public void stop() {
        stream.removeListener(this);
        stream.stop();
        resolvedProfile = null;
        // The stream and the view are cleared by CompanionState.leaveStreaming(), which is the one
        // place that knows the session is over.
    }

    @Override
    public void onTelemetrySnapshot(JSONObject snapshot) {
        if (!snapshot.optBoolean("attached", false)) {
            // Connected while nothing was being read. There is no profile yet, so there is nothing
            // to resolve; the next snapshot arrives when a game attaches.
            clearView();
            return;
        }

        String profile = snapshot.optString("profile", null);
        if (profile == null || profile.isEmpty()) {
            clearView();
            return;
        }
        if (profile.equals(resolvedProfile)) {
            return;
        }
        resolvedProfile = profile;

        Integer contract = snapshot.has("contract") ? snapshot.optInt("contract") : null;
        String html = views.find(profile, contract);
        if (html == null) {
            LimeLog.info("Telemetry: no view installed for profile '" + profile + "'");
        }
        state.setTelemetryView(html);
    }

    @Override
    public void onTelemetryDiff(JSONObject diff) {
        // Frames go straight to the panel. Nothing here needs them.
    }

    @Override
    public void onTelemetryDetached() {
        clearView();
    }

    private void clearView() {
        resolvedProfile = null;
        state.setTelemetryView(null);
    }
}
