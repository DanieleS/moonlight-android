package com.limelight.companion;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ties a stream session's telemetry together: follows the host's stream, works out which view draws
 * it, and hands both to {@link CompanionState} for the panel to pick up.
 *
 * <h3>Why the view is resolved from a frame rather than at startup</h3>
 *
 * Which view to draw depends on the <em>contract</em> of the profile scry chose, and that choice is
 * made against the running game's memory on the host — nothing here knows it in advance, and the app
 * name is no substitute. So the view is resolved from the first snapshot, which is the moment the
 * contract becomes known. Until then the panel shows the performance stats, which is the correct thing to
 * show for a game with no view at all.
 */
public class TelemetryController implements TelemetryStream.Listener {

    private final CompanionState state;
    private final TelemetryViewStore views;
    private final TelemetryStream stream;
    /**
     * Where views are resolved. Finding one reads files and may download from GitHub Pages, and
     * snapshots arrive on the main thread, which may do neither. One thread, so two resolves never
     * race to write the same cached file.
     */
    private final ExecutorService resolver = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "TelemetryViews");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    /** The contract a view was last resolved for, so an unchanged one is not re-resolved. */
    private String resolvedContract;
    /** Whether the missing contract was already reported, so it is said once per session. */
    private boolean loggedNoContract;

    public TelemetryController(Context context, NvHTTP http, CompanionState state) {
        this.state = state;
        // A view pushed onto the device by hand wins, so one can be tried before it is published.
        this.views = new TelemetryViewStore.Chain(
                new TelemetryViewStore.LocalFolder(context),
                new TelemetryViewStore.Published(context));
        this.stream = new TelemetryStream(http);
    }

    public void start() {
        state.setTelemetryStream(stream);
        stream.addListener(this);
        stream.start();
    }

    public void stop() {
        resolver.shutdownNow();
        stream.removeListener(this);
        stream.stop();
        resolvedContract = null;
        // The stream and the view are cleared by CompanionState.leaveStreaming(), which is the one
        // place that knows the session is over.
    }

    @Override
    public void onTelemetrySnapshot(JSONObject snapshot) {
        if (!snapshot.optBoolean("attached", false)) {
            // Connected while nothing was being read. There is no contract yet, so there is nothing
            // to resolve; the next snapshot arrives when a game attaches.
            clearView();
            return;
        }

        TelemetryViewStore.Contract contract = TelemetryViewStore.Contract.from(snapshot);
        String profile = snapshot.optString("profile", "");
        if (contract == null) {
            // No contract with an id, which is also what an older host sends. There is nothing to
            // pick a view by, and a view picked by the profile's label could draw values it was not
            // written for.
            if (!loggedNoContract) {
                LimeLog.info("Telemetry: profile '" + profile + "' names no contract; showing the stats");
                loggedNoContract = true;
            }
            clearView();
            return;
        }
        String key = contract.toString();
        if (key.equals(resolvedContract)) {
            return;
        }
        resolvedContract = key;

        resolver.execute(() -> {
            String html = views.find(contract);
            if (html == null) {
                LimeLog.info("Telemetry: no view reads contract " + key + " (profile '" + profile + "')");
            }
            main.post(() -> {
                // The game may have moved on, or detached, while the view was being fetched.
                if (key.equals(resolvedContract)) {
                    state.setTelemetryView(html);
                }
            });
        });
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
        resolvedContract = null;
        state.setTelemetryView(null);
    }
}
