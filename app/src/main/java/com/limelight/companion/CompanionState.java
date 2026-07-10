package com.limelight.companion;

import android.os.SystemClock;

/**
 * The data the companion surface draws. It outlives any single Presentation, so re-hosting
 * the surface on another Activity does not restart the counters.
 */
public class CompanionState {

    private final long startedAtMillis = SystemClock.elapsedRealtime();
    private int ticks;

    public void tick() {
        ticks++;
    }

    public int getTicks() {
        return ticks;
    }

    public long getUptimeSeconds() {
        return (SystemClock.elapsedRealtime() - startedAtMillis) / 1000;
    }
}
