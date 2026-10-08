package com.limelight.stats;

import android.content.Context;

import com.limelight.nvstream.http.NvHTTP;

import java.io.IOException;

/**
 * One NvHTTP to a host, made on first use and shared by everything a screen loads from it — the
 * statistics, the per-game sheet, the achievement icons the host keeps on disk. Making one is not
 * free (it builds the TLS state and may resolve the address), so it is made off the main thread,
 * by whichever request needs it first.
 */
public class HostSession {
    private final Context context;
    private final HostLink link;
    private NvHTTP http;

    public HostSession(Context context, HostLink link) {
        this.context = context.getApplicationContext();
        this.link = link;
    }

    /** A session over a connection someone already made, such as the stream's. */
    public HostSession(Context context, NvHTTP http) {
        this.context = context.getApplicationContext();
        this.link = null;
        this.http = http;
    }

    /** Blocks; never call on the main thread. */
    public synchronized NvHTTP http() throws IOException {
        if (http == null) {
            if (link == null) {
                throw new IOException("No host to connect to");
            }
            http = link.open(context);
        }
        return http;
    }

    /** The link this session was made from, or null when it wraps an existing connection. */
    public HostLink getLink() {
        return link;
    }
}
