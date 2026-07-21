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

import java.util.List;

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
 * The panel takes the gamepad focus only while its in-game menu is open (see
 * {@link CompanionPresentation}); a back gesture then closes the menu rather than the panel. With
 * no menu up it can be dismissed with a back gesture, and turned on again from the library bar or
 * the in-stream menu.
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

    // Who owns the secondary screen lives in CompanionState, alongside what there is to show; this
    // class is the part that acts on it, attaching and tearing down the panel to match.

    // Invoked when the panel's own menu button is pressed; the stream activity registers it to
    // raise the in-game menu here.
    private Runnable menuRequestListener;

    // Invoked when the panel's menu opens and closes; the stream activity registers these to
    // track whether it should be steering the gamepad into the menu drawn here.
    private Runnable menuOpenedListener;
    private Runnable menuClosedListener;

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
        manager.state.setOwnerListener(manager::onOwnerChanged);
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
        CompanionState.getInstance().setOwner(
                visible ? CompanionState.Owner.PANEL : CompanionState.Owner.OFF);
    }

    /** Flip the panel between on and off. */
    public static void toggle() {
        CompanionState state = CompanionState.getInstance();
        setVisible(state.getOwner() != CompanionState.Owner.PANEL);
    }

    // --- The in-game menu, when it is drawn on the companion panel ---

    /** Register what the panel's menu button should do; the stream activity raises the menu. */
    public static void setMenuRequestListener(Runnable listener) {
        if (instance != null) {
            instance.menuRequestListener = listener;
        }
    }

    /**
     * Register what should happen when the panel's menu opens and closes. The stream activity
     * uses these to track, on its own, whether it should be steering the gamepad into this menu —
     * the panel takes no focus, so the pad never leaves the activity, and it needs a reliable
     * signal rather than having to ask the panel each time.
     */
    public static void setMenuOpenedListener(Runnable listener) {
        if (instance != null) {
            instance.menuOpenedListener = listener;
        }
    }

    public static void setMenuClosedListener(Runnable listener) {
        if (instance != null) {
            instance.menuClosedListener = listener;
        }
    }

    /** The panel's menu button was pressed. */
    public static void requestOpenMenu() {
        if (instance != null && instance.menuRequestListener != null) {
            instance.menuRequestListener.run();
        }
    }

    /** The panel's menu just closed; tell the owner it no longer holds the gamepad. */
    static void onMenuClosed() {
        // Runs on the main thread, the same as the listener; keep it synchronous so the gamepad
        // is handed back to the game on this very frame rather than one behind.
        if (instance != null && instance.menuClosedListener != null) {
            instance.menuClosedListener.run();
        }
    }

    /** Draw the in-game menu on the panel, if one is up. Returns whether it was shown there. */
    public static boolean showMenu(String title, List<CompanionMenuItem> items) {
        if (instance == null || instance.presentation == null) {
            return false;
        }
        instance.presentation.showMenu(title, items);
        if (instance.menuOpenedListener != null) {
            instance.menuOpenedListener.run();
        }
        return true;
    }

    public static void hideMenu() {
        if (instance != null && instance.presentation != null) {
            instance.presentation.hideMenu();
        }
    }

    public static boolean isMenuOpen() {
        return instance != null && instance.presentation != null && instance.presentation.isMenuOpen();
    }

    public static void moveMenuSelection(int delta) {
        if (isMenuOpen()) {
            instance.presentation.moveSelection(delta);
        }
    }

    public static void activateMenuSelection() {
        if (isMenuOpen()) {
            instance.presentation.activateSelection();
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
                && CompanionState.getInstance().isPanelOwner()
                && DisplayTargets.findCompanionDisplay(activity, getDisplayIdOf(activity)) != null;
    }

    /**
     * Give the secondary screen up to a companion app, or take it back when the app is done.
     *
     * The panel is a {@code TYPE_PRESENTATION} window, which the platform layers above every
     * application window on that display — so an app launched there is invisible until the panel
     * actually goes away. Hiding it is the only way to let the app be seen.
     */
    public static void yieldToApp(boolean yield) {
        CompanionState state = CompanionState.getInstance();
        if (yield) {
            state.setOwner(CompanionState.Owner.COMPANION_APP);
        } else if (state.getOwner() == CompanionState.Owner.COMPANION_APP) {
            // Only take the screen back from an app. If the user hid the panel meanwhile, their
            // choice outranks ours.
            state.setOwner(CompanionState.Owner.PANEL);
        }
    }

    /** The owner region moved: put the panel up or take it down to match. */
    private void onOwnerChanged() {
        if (state.isPanelOwner()) {
            sync();
        } else {
            detach();
        }
    }

    /** Ensure a panel is up for the current owner, if conditions allow and one is not already. */
    private void sync() {
        if (owner == null || startedActivities == 0 || !isEnabled(application)
                || !state.isPanelOwner()) {
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
        if (owner == null || startedActivities == 0 || !isEnabled(application)
                || !state.isPanelOwner()) {
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
            state.setOwner(CompanionState.Owner.OFF);
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
