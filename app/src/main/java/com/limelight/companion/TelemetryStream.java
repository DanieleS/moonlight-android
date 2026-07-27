package com.limelight.companion;

import android.os.Handler;
import android.os.Looper;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.ResponseBody;

/**
 * Follows a Vibepollo host's game telemetry stream for the duration of a stream session.
 *
 * <h3>Why the frames are not merged before being handed on</h3>
 *
 * The host sends one complete {@code snapshot} on connect and then one {@code diff} per tick,
 * carrying only what that tick changed. Those diffs are forwarded to the renderer as they arrive
 * rather than folded into a running picture first, because <em>the changes are the point</em>: a
 * view that wants to flash on a hit or count an event needs to see the hit, and a merged state
 * where health went 100 → 90 → 100 between two reads looks exactly like nothing having happened.
 *
 * <p>The picture is still accumulated here, for one narrow purpose: a renderer that appears (or
 * reloads) mid-game has missed every diff so far, and needs a snapshot to start from. That is what
 * {@link #getPicture()} is for.
 *
 * <p>All listener callbacks land on the main thread. Reading happens on its own thread, because the
 * connection is deliberately long-lived and silent whenever the game is quiet.
 */
public class TelemetryStream {

    /** Backoff between reconnection attempts. The host is on the LAN; this is not a busy loop. */
    private static final long RECONNECT_DELAY_MS = 2000;

    public interface Listener {
        /**
         * A complete picture. Sent on connect, and again after any gap the host could not bridge,
         * so a renderer may treat it as "forget what you had and use this".
         */
        void onTelemetrySnapshot(JSONObject snapshot);

        /** One tick's changes. Values may be null, meaning the watch became unreadable. */
        void onTelemetryDiff(JSONObject diff);

        /** The game went away. Whatever is on screen is now a dead game's numbers. */
        void onTelemetryDetached();
    }

    private final NvHTTP http;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Thread thread;
    private volatile boolean stopped;
    private volatile ResponseBody body;

    /** Guarded by {@code this}: touched by the reader thread and read from the main thread. */
    private JSONObject picture;

    /** Copy-on-write because listeners are added and removed while frames are being posted. */
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public TelemetryStream(NvHTTP http) {
        this.http = http;
    }

    /**
     * Follow the stream. There is more than one interested party and they come and go
     * independently: a panel that renders frames appears and disappears as the surface is
     * re-hosted, while whoever resolves which view to draw watches for the whole session.
     */
    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * The last complete picture, or null if none has arrived. A renderer that starts late calls
     * this once to initialise itself, then follows the diffs.
     */
    public synchronized JSONObject getPicture() {
        return picture;
    }

    public void start() {
        if (thread != null) {
            return;
        }
        stopped = false;
        thread = new Thread(this::run, "TelemetryStream");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        stopped = true;
        // Closing the body is what unblocks the reader: it is parked in a read with no timeout, so
        // interrupting the thread alone would not wake it.
        ResponseBody open = body;
        if (open != null) {
            open.close();
        }
        thread = null;
    }

    private void run() {
        while (!stopped) {
            try {
                readStream();
            } catch (FileNotFoundException e) {
                // No endpoint, or telemetry switched off host-side. Neither changes while we wait,
                // so this is a stop rather than a retry.
                LimeLog.info("Telemetry: host does not serve telemetry");
                return;
            } catch (HostHttpResponseException e) {
                if (e.getErrorCode() == 401) {
                    // This client lacks the permission. Only the user can change that, on the host.
                    LimeLog.info("Telemetry: not permitted for this client");
                    return;
                }
                // 403 means the host does not consider us the client currently streaming, which can
                // become true a moment from now — the stream may still be coming up.
                LimeLog.info("Telemetry: host refused the stream (" + e.getErrorCode() + ")");
            } catch (IOException e) {
                if (!stopped) {
                    LimeLog.info("Telemetry: stream ended (" + e.getMessage() + ")");
                }
            }

            if (stopped) {
                return;
            }
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * Read one connection's worth of events, returning when it ends.
     *
     * <p>Events are delimited by a blank line, per SSE. Comment lines (a leading colon) are the
     * host's keepalive and carry nothing; they exist so that a client that has gone away is noticed
     * even while the game is quiet.
     */
    private void readStream() throws IOException, HostHttpResponseException {
        ResponseBody open = http.openTelemetryStream();
        body = open;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(open.byteStream()))) {
            String event = null;
            StringBuilder data = new StringBuilder();

            String line;
            while ((line = reader.readLine()) != null) {
                if (stopped) {
                    return;
                }

                if (line.isEmpty()) {
                    if (event != null && data.length() > 0) {
                        dispatch(event, data.toString());
                    }
                    event = null;
                    data.setLength(0);
                } else if (line.startsWith(":")) {
                    // Keepalive.
                } else if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
                // `id:` is the host's revision. Nothing here needs it: this is a single ordered
                // connection, and a break in it is answered with a fresh snapshot rather than a
                // request to resume from a cursor.
            }
        } finally {
            body = null;
            open.close();
        }
    }

    private void dispatch(String event, String data) {
        JSONObject parsed;
        try {
            parsed = new JSONObject(data);
        } catch (JSONException e) {
            LimeLog.warning("Telemetry: unparseable " + event + " payload");
            return;
        }

        switch (event) {
            case "snapshot":
                synchronized (this) {
                    picture = parsed;
                }
                main.post(() -> {
                    for (Listener l : listeners) {
                        l.onTelemetrySnapshot(parsed);
                    }
                });
                break;

            case "diff":
                merge(parsed);
                main.post(() -> {
                    for (Listener l : listeners) {
                        l.onTelemetryDiff(parsed);
                    }
                });
                break;

            case "detached":
                synchronized (this) {
                    picture = null;
                }
                main.post(() -> {
                    for (Listener l : listeners) {
                        l.onTelemetryDetached();
                    }
                });
                break;

            default:
                // An event this build does not know. Ignored rather than treated as an error, so a
                // newer host can add one without breaking this client.
                break;
        }
    }

    /**
     * Fold one diff into the accumulated picture.
     *
     * <p>A null value is stored rather than removed: the host uses null for "this watch became
     * unreadable", which is a different claim from the watch not existing, and a renderer is
     * entitled to tell them apart.
     */
    private synchronized void merge(JSONObject diff) {
        if (picture == null) {
            // Diffs before a snapshot cannot be placed. Dropping them is safe because the host
            // opens every connection with one.
            return;
        }
        JSONObject values = picture.optJSONObject("values");
        if (values == null) {
            values = new JSONObject();
            try {
                picture.put("values", values);
            } catch (JSONException e) {
                return;
            }
        }
        for (Iterator<String> it = diff.keys(); it.hasNext(); ) {
            String name = it.next();
            try {
                values.put(name, diff.get(name));
            } catch (JSONException e) {
                // A key that cannot be copied is one watch lost, not a reason to abandon the rest.
                LimeLog.warning("Telemetry: could not merge '" + name + "'");
            }
        }
    }
}
