package com.limelight.companion;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
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
 * visibly restarts. The re-host is make-before-break — the new panel is shown before the old
 * one is dismissed — so navigating between screens does not blink the panel.
 *
 * The panel can be dismissed with a back gesture on the secondary display; it then stays off
 * until the user turns it back on from the library bar or the in-stream menu.
 *
 * Everything here is best-effort: with no usable secondary display, or when the platform
 * refuses the window, the manager logs and stays idle so the rest of the app is unaffected.
 */
public class CompanionDisplayManager implements Application.ActivityLifecycleCallbacks {

    private static CompanionDisplayManager instance;

    private final Application application;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CompanionState state = CompanionState.getInstance();

    private DisplayManager displayManager;
    private CompanionPresentation presentation;
    private Activity owner;
    private int presentationDisplayId = Display.INVALID_DISPLAY;
    private int startedActivities;

    // Set when the user dismisses the panel (a back gesture) so it is not brought straight back;
    // cleared when they turn it on again.
    private boolean userSuppressed;

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
        instance = manager;
    }

    /**
     * The companion needs a display of its own. External monitor mode already spends both
     * panels: the stream on one, the touchpad controller on the other.
     */
    private static boolean isEnabled(Context context) {
        PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(context);
        return prefConfig.enableCompanionDisplay && !prefConfig.enableFullExDisplay;
    }

    /**
     * Whether the companion feature is switched on at all for the current context. The library
     * bar and the in-stream menu use this to decide whether to offer the toggle.
     */
    public static boolean isConfigured(Context context) {
        return isEnabled(context);
    }

    /** Whether a panel is on screen right now. */
    public static boolean isShowing() {
        return instance != null && instance.presentation != null;
    }

    /**
     * Turn the panel on or off by hand. Off is remembered so a back-gesture dismissal or an
     * explicit hide is not undone by the next screen change.
     */
    public static void setVisible(boolean visible) {
        if (instance != null) {
            instance.applyUserSuppressed(!visible);
        }
    }

    /** Flip the panel between on and off. */
    public static void toggle() {
        if (instance != null) {
            instance.applyUserSuppressed(!instance.userSuppressed);
        }
    }

    /**
     * Whether content handed to {@link CompanionState} will actually be shown somewhere: the
     * companion is switched on and a display exists to host it.
     *
     * Callers use this to decide whether to render something themselves instead. It answers for
     * the current moment, not for the whole stream: a companion panel that is powered off later
     * takes its content with it.
     */
    public static boolean isCompanionAvailable(Activity activity) {
        return isEnabled(activity)
                && !(instance != null && instance.userSuppressed)
                && DisplayTargets.findCompanionDisplay(activity, getDisplayIdOf(activity)) != null;
    }

    private void applyUserSuppressed(boolean suppressed) {
        userSuppressed = suppressed;
        if (suppressed) {
            detach();
        } else {
            sync();
        }
    }

    /** Ensure a panel is up for the current owner, if conditions allow and one is not already. */
    private void sync() {
        if (owner == null || startedActivities == 0 || !isEnabled(application) || userSuppressed) {
            detach();
            return;
        }

        if (presentation != null) {
            return;
        }

        Display display = findDisplay();
        if (display == null) {
            LimeLog.info("CompanionDisplay: no secondary display available");
            return;
        }

        CompanionPresentation shown = createAndShow(display);
        if (shown == null) {
            return;
        }

        presentation = shown;
        presentationDisplayId = display.getDisplayId();
        LimeLog.info("CompanionDisplay: attached to display " + presentationDisplayId
                + " via " + owner.getClass().getSimpleName());
    }

    /**
     * Move the panel onto the current owner without a visible gap: show the replacement first,
     * then dismiss the one it replaces.
     */
    private void rehost() {
        if (owner == null || startedActivities == 0 || !isEnabled(application) || userSuppressed) {
            detach();
            return;
        }

        Display display = findDisplay();
        if (display == null) {
            detach();
            return;
        }

        CompanionPresentation replacement = createAndShow(display);
        if (replacement == null) {
            // Keep whatever is already up rather than blanking the panel on a failed swap.
            return;
        }

        CompanionPresentation previous = presentation;
        presentation = replacement;
        presentationDisplayId = display.getDisplayId();
        if (previous != null) {
            // Its listener is cleared so this deliberate dismissal is not read as the user
            // closing the panel, and CompanionState keeps the replacement's listener.
            previous.setOnDismissListener(null);
            previous.dismiss();
        }
        LimeLog.info("CompanionDisplay: re-hosted on " + owner.getClass().getSimpleName());
    }

    private Display findDisplay() {
        return DisplayTargets.findCompanionDisplay(application, getDisplayIdOf(owner));
    }

    private CompanionPresentation createAndShow(Display display) {
        CompanionPresentation newPresentation = new CompanionPresentation(owner, display, state);
        newPresentation.setOnDismissListener(this::onPresentationDismissed);
        try {
            newPresentation.show();
        } catch (WindowManager.InvalidDisplayException e) {
            // The display went away between selecting it and showing the window.
            LimeLog.warning("CompanionDisplay: display " + display.getDisplayId()
                    + " vanished: " + e.getMessage());
            return null;
        }
        return newPresentation;
    }

    /**
     * The panel was dismissed while it was the current one. If its display is still alive, the
     * user closed it with a back gesture, so keep it off until they ask for it again; otherwise
     * the display went away, so let sync() bring the panel back when a display returns.
     */
    private void onPresentationDismissed(DialogInterface dialog) {
        if (presentation != dialog) {
            return;
        }

        int dismissedId = presentationDisplayId;
        presentation = null;
        presentationDisplayId = Display.INVALID_DISPLAY;

        Display display = displayManager.getDisplay(dismissedId);
        boolean displayAlive = display != null && display.isValid()
                && display.getState() == Display.STATE_ON;
        if (displayAlive) {
            LimeLog.info("CompanionDisplay: dismissed by the user; staying off until turned on");
            userSuppressed = true;
        } else {
            handler.post(this::sync);
        }
    }

    private void detach() {
        if (presentation == null) {
            return;
        }

        LimeLog.info("CompanionDisplay: detaching from display " + presentationDisplayId);
        // Clear the dismiss listener first so our own dismissal isn't mistaken for the user
        // closing the panel out from under us.
        presentation.setOnDismissListener(null);
        presentation.dismiss();
        presentation = null;
        presentationDisplayId = Display.INVALID_DISPLAY;
    }

    /**
     * The display the Activity itself lives on. With Artemis' external monitor mode that is
     * not the default display, so it is resolved rather than assumed.
     */
    private static int getDisplayIdOf(Activity activity) {
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
            // Re-host on the Activity that now owns the foreground, without blanking the panel.
            owner = activity;
            rehost();
        } else {
            sync();
        }
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
