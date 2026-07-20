package com.limelight.companion;

import android.app.Activity;
import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;

import com.limelight.LimeLog;

import java.util.Locale;

/**
 * Enumerates the physical displays and picks the one to use as a companion panel.
 *
 * Display ids are never assumed: on the AYN Thor the primary display is the large 6" panel
 * and the secondary is the small 3.92" one, which is the opposite of most dual-screen
 * handhelds. Selection is therefore done by geometry, not by index.
 */
public final class DisplayTargets {

    private DisplayTargets() {
    }

    /**
     * Logs every display the system reports. The output is the input for deciding how to
     * tell an internal companion panel apart from a hot-plugged external monitor, which
     * cannot be determined from the public API alone.
     */
    public static void dumpDisplays(Context context) {
        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) {
            LimeLog.warning("CompanionDisplay: no DisplayManager available");
            return;
        }

        Display[] displays = displayManager.getDisplays();
        LimeLog.info("CompanionDisplay: " + displays.length + " display(s) reported");

        for (Display display : displays) {
            Point size = getRealSize(display);
            LimeLog.info(String.format(Locale.US,
                    "CompanionDisplay: id=%d name='%s' size=%dx%d refresh=%.1fHz state=%s valid=%b flags=0x%x [%s]",
                    display.getDisplayId(),
                    display.getName(),
                    size.x,
                    size.y,
                    display.getRefreshRate(),
                    stateToString(display.getState()),
                    display.isValid(),
                    display.getFlags(),
                    flagsToString(display.getFlags())));
        }
    }

    /**
     * Returns the display to host the companion surface, or null when there is none.
     *
     * @param hostDisplayId the display the streaming Activity itself lives on. It is not
     *                      necessarily {@link Display#DEFAULT_DISPLAY}: with Artemis' external
     *                      monitor mode the Activity is launched onto a secondary display.
     */
    public static Display findCompanionDisplay(Context context, int hostDisplayId) {
        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) {
            return null;
        }

        Display best = null;
        long bestArea = Long.MAX_VALUE;

        for (Display display : displayManager.getDisplays()) {
            if (display.getDisplayId() == hostDisplayId) {
                continue;
            }
            if (!display.isValid() || display.getState() != Display.STATE_ON) {
                continue;
            }
            // Private displays are virtual displays owned by another app, not something we can draw on.
            if ((display.getFlags() & Display.FLAG_PRIVATE) != 0) {
                continue;
            }

            Point size = getRealSize(display);
            long area = (long) size.x * (long) size.y;
            if (area > 0 && area < bestArea) {
                bestArea = area;
                best = display;
            }
        }

        return best;
    }

    /**
     * The id of the companion display for an Activity, resolving the display the Activity itself
     * lives on rather than assuming the default one, or {@link Display#INVALID_DISPLAY} when there
     * is no companion display to use.
     */
    public static int findCompanionDisplayId(Activity activity) {
        Display host = activity.getWindowManager().getDefaultDisplay();
        int hostId = host != null ? host.getDisplayId() : Display.DEFAULT_DISPLAY;
        Display companion = findCompanionDisplay(activity, hostId);
        return companion != null ? companion.getDisplayId() : Display.INVALID_DISPLAY;
    }

    private static Point getRealSize(Display display) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode mode = display.getMode();
            if (mode != null) {
                return new Point(mode.getPhysicalWidth(), mode.getPhysicalHeight());
            }
        }

        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        return new Point(metrics.widthPixels, metrics.heightPixels);
    }

    private static String stateToString(int state) {
        switch (state) {
            case Display.STATE_OFF: return "OFF";
            case Display.STATE_ON: return "ON";
            case Display.STATE_DOZE: return "DOZE";
            case Display.STATE_DOZE_SUSPEND: return "DOZE_SUSPEND";
            case Display.STATE_UNKNOWN: return "UNKNOWN";
            default: return "STATE_" + state;
        }
    }

    private static String flagsToString(int flags) {
        StringBuilder sb = new StringBuilder();
        if ((flags & Display.FLAG_PRESENTATION) != 0) sb.append("PRESENTATION ");
        if ((flags & Display.FLAG_PRIVATE) != 0) sb.append("PRIVATE ");
        if ((flags & Display.FLAG_SECURE) != 0) sb.append("SECURE ");
        if ((flags & Display.FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0) sb.append("PROTECTED_BUFFERS ");
        return sb.toString().trim();
    }
}
