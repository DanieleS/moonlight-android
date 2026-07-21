package com.limelight.companion;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.MenuSheet;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Opens a per-game companion app on the secondary screen, and lets the user pick which one.
 *
 * The launch reuses the same display selection as the companion panel (the smaller secondary
 * screen, chosen by geometry in {@link DisplayTargets}) and the same {@code setLaunchDisplayId}
 * mechanism the external-display touchpad already relies on, so it works wherever that does.
 *
 * Everything is best-effort: with no secondary display, no chosen app, or a platform that
 * refuses the launch, it logs and does nothing rather than disturbing the stream.
 */
public final class CompanionAppLauncher {

    /** Handed the component the user picked, so a caller can launch it straight away. */
    public interface OnChosen {
        void onChosen(ComponentName component);
    }

    private CompanionAppLauncher() {
    }

    /**
     * Whether a companion app can be offered here at all: the feature is on, we are not in the
     * external-display mode that already spends both screens, and a secondary display exists.
     */
    public static boolean isSupported(Activity host) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(host);
        if (!prefs.enableCompanionApp || prefs.enableFullExDisplay) {
            return false;
        }
        return DisplayTargets.findCompanionDisplayId(host) != Display.INVALID_DISPLAY;
    }

    /**
     * Launch the app chosen for this game, if any. When {@code userInitiated} and none is chosen,
     * the picker opens so the choice can be made and launched on the spot.
     *
     * @return true if an app was launched.
     */
    public static boolean launchForGame(Activity host, String appUuid, String appName, boolean userInitiated) {
        ComponentName chosen = CompanionApps.get(host, appUuid, appName);
        if (chosen == null) {
            if (userInitiated) {
                showPicker(host, appUuid, appName, component -> launch(host, component, true));
            }
            return false;
        }
        return launch(host, chosen, userInitiated);
    }

    private static boolean launch(Activity host, ComponentName component, boolean announce) {
        int displayId = DisplayTargets.findCompanionDisplayId(host);
        if (displayId == Display.INVALID_DISPLAY || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (announce) {
                Toast.makeText(host, R.string.companion_app_no_display, Toast.LENGTH_SHORT).show();
            }
            return false;
        }

        Intent launchIntent = host.getPackageManager().getLaunchIntentForPackage(component.getPackageName());
        if (launchIntent == null) {
            // Fall back to the exact component when the package has no default launcher entry.
            launchIntent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(component);
        }
        // A task of its own on the companion display, kept clear of the game's own task.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Bundle options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle();
        // The panel must come down first: it is a presentation window, so it sits above anything
        // launched onto that display and would otherwise hide the app completely.
        CompanionDisplayManager.yieldToApp(true);
        try {
            host.startActivity(launchIntent, options);
            return true;
        } catch (Exception e) {
            LimeLog.warning("CompanionApp: failed to launch " + component + ": " + e.getMessage());
            // Nothing took the screen, so take it back rather than leaving it dark.
            CompanionDisplayManager.yieldToApp(false);
            if (announce) {
                Toast.makeText(host, R.string.companion_app_launch_failed, Toast.LENGTH_SHORT).show();
            }
            return false;
        }
    }

    /**
     * Show every launchable app as a sheet; the pick is stored for this game and handed back.
     * The current choice, if any, wears the check, and a "None" row clears it.
     */
    public static void showPicker(Activity host, String appUuid, String appName, OnChosen onChosen) {
        final PackageManager pm = host.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);

        // Drop our own app: opening it beside itself is never the intent.
        String self = host.getPackageName();
        List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
            if (info.activityInfo != null && !self.equals(info.activityInfo.packageName)) {
                apps.add(info);
            }
        }

        final Collator collator = Collator.getInstance();
        Collections.sort(apps, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                return collator.compare(a.loadLabel(pm).toString(), b.loadLabel(pm).toString());
            }
        });

        MenuSheet sheet = new MenuSheet(host).setTitle(host.getString(R.string.companion_app_pick_title));

        ComponentName current = CompanionApps.get(host, appUuid, appName);
        if (current != null) {
            // Clearing comes first so it is always in reach.
            sheet.add(host.getString(R.string.companion_app_none), () -> {
                CompanionApps.clear(host, appUuid, appName);
                Toast.makeText(host, R.string.companion_app_cleared, Toast.LENGTH_SHORT).show();
            });
        }

        for (ResolveInfo info : apps) {
            final ComponentName component = new ComponentName(
                    info.activityInfo.packageName, info.activityInfo.name);
            final CharSequence label = info.loadLabel(pm);
            sheet.addChecked(label, component.equals(current), () -> {
                CompanionApps.set(host, appUuid, appName, component);
                if (onChosen != null) {
                    onChosen.onChosen(component);
                }
            });
        }

        sheet.showCentered(host);
    }

    /** A readable name for a stored component, falling back to the package when it is gone. */
    public static CharSequence labelFor(Context context, ComponentName component) {
        PackageManager pm = context.getPackageManager();
        try {
            return pm.getActivityInfo(component, 0).loadLabel(pm);
        } catch (PackageManager.NameNotFoundException e) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(component.getPackageName(), 0);
                return pm.getApplicationLabel(ai);
            } catch (PackageManager.NameNotFoundException e2) {
                return component.getPackageName();
            }
        }
    }
}
