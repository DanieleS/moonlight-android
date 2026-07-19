package com.limelight.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

/**
 * When each game was last launched, as seen by this device.
 *
 * <p>This is deliberately local. The host knows when a game was really last played — including
 * sessions played at the PC itself — but it does not report that, so "last played" here means
 * "last launched from this device". Entries are kept per computer, since the same game on two
 * hosts is two library entries.
 */
public class AppLaunchHistory {
    private static final String PREF_FILENAME = "AppLaunchHistory";

    private final SharedPreferences prefs;
    private final String computerUuid;

    public AppLaunchHistory(Context context, String computerUuid) {
        this.prefs = context.getSharedPreferences(PREF_FILENAME, Context.MODE_PRIVATE);
        this.computerUuid = computerUuid == null ? "" : computerUuid;
    }

    /** Milliseconds since the epoch, or 0 when this device has never launched the game. */
    public long getLastPlayed(NvApp app) {
        return prefs.getLong(keyFor(computerUuid, app), 0);
    }

    /** Notes that a game is being launched now. */
    public static void recordLaunch(Context context, ComputerDetails computer, NvApp app) {
        if (computer == null || app == null) {
            return;
        }
        context.getSharedPreferences(PREF_FILENAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(keyFor(computer.uuid, app), System.currentTimeMillis())
                .apply();
    }

    // The app's UUID is the stable identity where the host reports one; the numeric ID is the
    // fallback for hosts that don't, and can be reassigned when the host's app list changes.
    private static String keyFor(String computerUuid, NvApp app) {
        String appUuid = app.getAppUUID();
        String appKey = appUuid == null || appUuid.isEmpty()
                ? Integer.toString(app.getAppId())
                : appUuid.toUpperCase();
        return (computerUuid == null ? "" : computerUuid) + "|" + appKey;
    }
}
