package com.limelight.ui;

/**
 * Raises and dismisses an on-screen keyboard when the host reports that a text field took or
 * lost focus on its desktop.
 *
 * The keyboard goes where typing is least in the way: the companion panel on the second screen
 * when one is up, the control screen in external display mode, and the stream's own screen
 * otherwise. Only a keyboard raised here is ever taken down here: one the user opened by hand
 * stays up when the host reports that focus left the field.
 *
 * Main thread only. The Android side is behind {@link Keyboards} so the rules can be tested
 * without a device.
 */
public class TextFocusKeyboard {

    // These mirror LI_TEXT_FOCUS_* in moonlight-common-c's Limelight.h
    public static final byte FOCUS_NONE = 0;
    public static final byte FOCUS_TEXT = 1;
    public static final byte FOCUS_NUMERIC = 2;
    public static final byte FOCUS_PASSWORD = 3;

    public enum Target {
        /** The companion panel on the second screen */
        COMPANION,
        /** The touchpad controller screen of external display mode */
        CONTROL_SCREEN,
        /** The screen the stream is on */
        MAIN,
    }

    public interface Keyboards {
        boolean isCompanionShowing();

        boolean isControlScreenShowing();

        /**
         * Bring a keyboard up on the target.
         *
         * @return true if this call raised it, false if one was already up there or it could not
         *         be shown. Only a keyboard this call raised is later dismissed by a focus change.
         */
        boolean show(Target target, byte focusType);

        void hide(Target target);
    }

    private final Keyboards keyboards;

    // Where the keyboard we raised is, or null when there is none of ours on screen
    private Target raisedOn;

    public TextFocusKeyboard(Keyboards keyboards) {
        this.keyboards = keyboards;
    }

    public static boolean isTextField(byte focusType) {
        return focusType == FOCUS_TEXT || focusType == FOCUS_NUMERIC || focusType == FOCUS_PASSWORD;
    }

    public static Target pickTarget(boolean companionShowing, boolean controlScreenShowing) {
        if (companionShowing) {
            return Target.COMPANION;
        }
        if (controlScreenShowing) {
            return Target.CONTROL_SCREEN;
        }
        return Target.MAIN;
    }

    /** The host reported what has focus now. */
    public void onFocus(byte focusType) {
        if (!isTextField(focusType)) {
            dismiss();
            return;
        }

        Target target = pickTarget(keyboards.isCompanionShowing(), keyboards.isControlScreenShowing());
        if (raisedOn != null && raisedOn != target) {
            // The panel came or went since we raised it: move the keyboard rather than leave
            // one on each screen.
            keyboards.hide(raisedOn);
            raisedOn = null;
        }

        if (keyboards.show(target, focusType)) {
            raisedOn = target;
        }
    }

    /**
     * The user opened, closed or toggled a keyboard themselves. From here on it is theirs, so a
     * host report that focus left the field must not take it away.
     */
    public void onManualKeyboardUse() {
        raisedOn = null;
    }

    /** Take down the keyboard we raised, if any. The stream ending calls this too. */
    public void dismiss() {
        if (raisedOn != null) {
            Target target = raisedOn;
            raisedOn = null;
            keyboards.hide(target);
        }
    }

    public Target getRaisedOn() {
        return raisedOn;
    }
}
