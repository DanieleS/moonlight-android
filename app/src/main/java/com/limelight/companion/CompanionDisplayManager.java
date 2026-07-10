package com.limelight.companion;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * Keeps a companion surface on the secondary display for as long as the app is in the
 * foreground, across every Activity.
 *
 * A Presentation is a Dialog, so it needs an Activity to own its window. The owner therefore
 * changes as the user moves between screens: the surface is re-hosted on whichever Activity
 * is currently resumed, while {@link CompanionState} carries the content across so nothing
 * visibly restarts.
 *
 * Everything here is best-effort: with no usable secondary display, or when the platform
 * refuses the window, the manager logs and stays idle so the rest of the app is unaffected.
 */
public class CompanionDisplayManager implements Application.ActivityLifecycleCallbacks {

    private final Application application;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CompanionState state = new CompanionState();

    private DisplayManager displayManager;
    private CompanionPresentation presentation;
    private Activity owner;
    private int presentationDisplayId = Display.INVALID_DISPLAY;
    private int startedActivities;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            sync();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            if (displayId == presentationDisplayId) {
                detach();
            }
            sync();
        }

        @Override
        public void onDisplayChanged(int displayId) {
            // Our panel may have just been powered off, or a candidate may have woken up.
            if (displayId == presentationDisplayId) {
                Display display = displayManager.getDisplay(displayId);
                if (display == null || !display.isValid() || display.getState() != Display.STATE_ON) {
                    detach();
                }
            }
            sync();
        }
    };

    private CompanionDisplayManager(Application application) {
        this.application = application;
    }

    /** Call once from {@code Application.onCreate}. */
    public static void install(Application application) {
        CompanionDisplayManager manager = new CompanionDisplayManager(application);

        manager.displayManager = (DisplayManager) application.getSystemService(Context.DISPLAY_SERVICE);
        if (manager.displayManager == null) {
            LimeLog.warning("CompanionDisplay: no DisplayManager, companion screen disabled");
            return;
        }

        DisplayTargets.dumpDisplays(application);
        application.registerActivityLifecycleCallbacks(manager);
        manager.displayManager.registerDisplayListener(manager.displayListener, manager.handler);
    }

    /**
     * The companion needs a display of its own. External monitor mode already spends both
     * panels: the stream on one, the touchpad controller on the other.
     */
    private boolean isEnabled() {
        PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(application);
        return prefConfig.enableCompanionDisplay && !prefConfig.enableFullExDisplay;
    }

    private void sync() {
        if (owner == null || startedActivities == 0 || !isEnabled()) {
            detach();
            return;
        }

        if (presentation != null) {
            return;
        }

        Display display = DisplayTargets.findCompanionDisplay(application, getDisplayIdOf(owner));
        if (display == null) {
            LimeLog.info("CompanionDisplay: no secondary display available");
            return;
        }

        CompanionPresentation newPresentation = new CompanionPresentation(owner, display, state);
        try {
            newPresentation.show();
        } catch (WindowManager.InvalidDisplayException e) {
            // The display went away between selecting it and showing the window.
            LimeLog.warning("CompanionDisplay: display " + display.getDisplayId() + " vanished: " + e.getMessage());
            return;
        }

        presentation = newPresentation;
        presentationDisplayId = display.getDisplayId();
        LimeLog.info("CompanionDisplay: attached to display " + presentationDisplayId
                + " via " + owner.getClass().getSimpleName());
    }

    private void detach() {
        if (presentation == null) {
            return;
        }

        LimeLog.info("CompanionDisplay: detaching from display " + presentationDisplayId);
        presentation.dismiss();
        presentation = null;
        presentationDisplayId = Display.INVALID_DISPLAY;
    }

    /**
     * The display the Activity itself lives on. With Artemis' external monitor mode that is
     * not the default display, so it is resolved rather than assumed.
     */
    private int getDisplayIdOf(Activity activity) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        return display != null ? display.getDisplayId() : Display.DEFAULT_DISPLAY;
    }

    // --- Activity lifecycle ---

    @Override
    public void onActivityStarted(Activity activity) {
        startedActivities++;
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (owner != activity) {
            // Re-host on the Activity that now owns the foreground.
            detach();
            owner = activity;
        }
        sync();
    }

    @Override
    public void onActivityStopped(Activity activity) {
        startedActivities--;
        if (startedActivities == 0) {
            // The whole app went to the background; do not squat the secondary display.
            detach();
        }
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (owner == activity) {
            detach();
            owner = null;
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }
}
