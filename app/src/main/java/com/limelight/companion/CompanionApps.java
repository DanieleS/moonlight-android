package com.limelight.companion;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * Per-game choice of a companion app to open on the secondary screen.
 *
 * Keyed by the game's stable identity — its Playnite UUID when the host provides one, or its
 * name otherwise — so the same game keeps its companion app across hosts and across relaunches.
 * Empty until the user picks one from the library; a game with no choice simply opens nothing.
 */
public final class CompanionApps {

    private static final String PREFS_NAME = "CompanionApps";

    private CompanionApps() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // The UUID is the stable key wherever the host gives one; the name is the fallback for
    // stock hosts. Null when we have neither, which no game should ever hit.
    private static String keyFor(String appUuid, String appName) {
        if (appUuid != null && !appUuid.isEmpty()) {
            return "uuid:" + appUuid.toUpperCase();
        }
        if (appName != null && !appName.isEmpty()) {
            return "name:" + appName.toLowerCase();
        }
        return null;
    }

    /** The component chosen for this game, or null when it has none. */
    public static ComponentName get(Context context, String appUuid, String appName) {
        String key = keyFor(appUuid, appName);
        if (key == null) {
            return null;
        }
        String flat = prefs(context).getString(key, null);
        return flat != null ? ComponentName.unflattenFromString(flat) : null;
    }

    public static void set(Context context, String appUuid, String appName, ComponentName component) {
        String key = keyFor(appUuid, appName);
        if (key == null || component == null) {
            return;
        }
        prefs(context).edit().putString(key, component.flattenToString()).apply();
    }

    public static void clear(Context context, String appUuid, String appName) {
        String key = keyFor(appUuid, appName);
        if (key == null) {
            return;
        }
        prefs(context).edit().remove(key).apply();
    }
}
