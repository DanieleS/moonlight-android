package com.limelight.ui;

import android.text.InputType;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TextFocusKeyboardTest {

    /** Records what was asked of each screen and keeps a keyboard per screen. */
    private static class FakeKeyboards implements TextFocusKeyboard.Keyboards {
        boolean companion;
        boolean controlScreen;
        final boolean[] up = new boolean[TextFocusKeyboard.Target.values().length];
        final List<String> calls = new ArrayList<>();

        @Override
        public boolean isCompanionShowing() {
            return companion;
        }

        @Override
        public boolean isControlScreenShowing() {
            return controlScreen;
        }

        @Override
        public boolean show(TextFocusKeyboard.Target target, byte focusType) {
            calls.add("show " + target);
            if (up[target.ordinal()]) {
                return false;
            }
            up[target.ordinal()] = true;
            return true;
        }

        @Override
        public void hide(TextFocusKeyboard.Target target) {
            calls.add("hide " + target);
            up[target.ordinal()] = false;
        }

        boolean isUp(TextFocusKeyboard.Target target) {
            return up[target.ordinal()];
        }
    }

    private FakeKeyboards keyboards;
    private TextFocusKeyboard keyboard;

    @Before
    public void setUp() {
        keyboards = new FakeKeyboards();
        keyboard = new TextFocusKeyboard(keyboards);
    }

    @Test
    public void companionPanelWinsOverEveryOtherScreen() {
        assertEquals(TextFocusKeyboard.Target.COMPANION, TextFocusKeyboard.pickTarget(true, true));
        assertEquals(TextFocusKeyboard.Target.COMPANION, TextFocusKeyboard.pickTarget(true, false));
        assertEquals(TextFocusKeyboard.Target.CONTROL_SCREEN, TextFocusKeyboard.pickTarget(false, true));
        assertEquals(TextFocusKeyboard.Target.MAIN, TextFocusKeyboard.pickTarget(false, false));
    }

    @Test
    public void onlyTextKindsRaiseAKeyboard() {
        assertFalse(TextFocusKeyboard.isTextField(TextFocusKeyboard.FOCUS_NONE));
        assertTrue(TextFocusKeyboard.isTextField(TextFocusKeyboard.FOCUS_TEXT));
        assertTrue(TextFocusKeyboard.isTextField(TextFocusKeyboard.FOCUS_NUMERIC));
        assertTrue(TextFocusKeyboard.isTextField(TextFocusKeyboard.FOCUS_PASSWORD));
        assertFalse(TextFocusKeyboard.isTextField((byte) 4));
    }

    @Test
    public void raisesOnTheCompanionAndTakesItDownWhenFocusLeaves() {
        keyboards.companion = true;

        keyboard.onFocus(TextFocusKeyboard.FOCUS_TEXT);
        assertTrue(keyboards.isUp(TextFocusKeyboard.Target.COMPANION));
        assertFalse(keyboards.isUp(TextFocusKeyboard.Target.MAIN));

        keyboard.onFocus(TextFocusKeyboard.FOCUS_NONE);
        assertFalse(keyboards.isUp(TextFocusKeyboard.Target.COMPANION));
        assertNull(keyboard.getRaisedOn());
    }

    @Test
    public void fallsBackToTheMainScreenWithoutACompanion() {
        keyboard.onFocus(TextFocusKeyboard.FOCUS_PASSWORD);
        assertTrue(keyboards.isUp(TextFocusKeyboard.Target.MAIN));
        assertEquals(TextFocusKeyboard.Target.MAIN, keyboard.getRaisedOn());
    }

    @Test
    public void movingBetweenFieldsKeepsTheSameKeyboard() {
        keyboards.companion = true;
        keyboard.onFocus(TextFocusKeyboard.FOCUS_TEXT);
        keyboard.onFocus(TextFocusKeyboard.FOCUS_NUMERIC);

        assertTrue(keyboards.isUp(TextFocusKeyboard.Target.COMPANION));
        assertEquals(TextFocusKeyboard.Target.COMPANION, keyboard.getRaisedOn());
        assertFalse(keyboards.calls.contains("hide COMPANION"));
    }

    @Test
    public void neverTakesDownAKeyboardTheUserOpened() {
        // The user already has the keyboard up when the host reports a text field
        keyboards.up[TextFocusKeyboard.Target.MAIN.ordinal()] = true;

        keyboard.onFocus(TextFocusKeyboard.FOCUS_TEXT);
        keyboard.onFocus(TextFocusKeyboard.FOCUS_NONE);

        assertTrue(keyboards.isUp(TextFocusKeyboard.Target.MAIN));
        assertFalse(keyboards.calls.contains("hide MAIN"));
    }

    @Test
    public void manualUseHandsTheKeyboardToTheUser() {
        keyboard.onFocus(TextFocusKeyboard.FOCUS_TEXT);
        keyboard.onManualKeyboardUse();
        keyboard.onFocus(TextFocusKeyboard.FOCUS_NONE);

        assertTrue(keyboards.isUp(TextFocusKeyboard.Target.MAIN));
    }

    @Test
    public void aDismissedKeyboardComesBackForTheNextField() {
        keyboards.companion = true;
        keyboard.onFocus(TextFocusKeyboard.FOCUS_TEXT);

        // Closed from the panel's own hide key
        keyboards.up[TextFocusKeyboard.Target.COMPANION.ordinal()] = false;
        keyboard.onManualKeyboardUse();

        keyboard.onFocus(TextFocusKeyboard.FOCUS_NONE);
        keyboard.onFocus(TextFocusKeyboard.FOCUS_TEXT);
        assertTrue(keyboards.isUp(TextFocusKeyboard.Target.COMPANION));
        assertEquals(TextFocusKeyboard.Target.COMPANION, keyboard.getRaisedOn());
    }

    @Test
    public void followsThePanelWhenItAppears() {
        keyboard.onFocus(TextFocusKeyboard.FOCUS_TEXT);
        assertTrue(keyboards.isUp(TextFocusKeyboard.Target.MAIN));

        keyboards.companion = true;
        keyboard.onFocus(TextFocusKeyboard.FOCUS_NUMERIC);

        assertFalse(keyboards.isUp(TextFocusKeyboard.Target.MAIN));
        assertTrue(keyboards.isUp(TextFocusKeyboard.Target.COMPANION));
        assertEquals(TextFocusKeyboard.Target.COMPANION, keyboard.getRaisedOn());
    }

    @Test
    public void dismissIsSafeWithNothingRaised() {
        keyboard.dismiss();
        assertTrue(keyboards.calls.isEmpty());
    }

    @Test
    public void streamEndTakesDownOurKeyboard() {
        keyboards.controlScreen = true;
        keyboard.onFocus(TextFocusKeyboard.FOCUS_TEXT);
        assertTrue(keyboards.isUp(TextFocusKeyboard.Target.CONTROL_SCREEN));

        keyboard.dismiss();
        assertFalse(keyboards.isUp(TextFocusKeyboard.Target.CONTROL_SCREEN));
        assertNull(keyboard.getRaisedOn());
    }

    @Test
    public void passwordFieldsKeepTheKeyboardFromSuggestingOrLearning() {
        int inputType = TextFocusKeyboard.inputTypeFor(TextFocusKeyboard.FOCUS_PASSWORD);
        assertEquals(InputType.TYPE_CLASS_TEXT, inputType & InputType.TYPE_MASK_CLASS);
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, inputType & InputType.TYPE_MASK_VARIATION);
        assertTrue((inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0);
    }

    @Test
    public void inputTypeFollowsTheField() {
        assertEquals(InputType.TYPE_CLASS_NUMBER, TextFocusKeyboard.inputTypeFor(TextFocusKeyboard.FOCUS_NUMERIC));
        assertEquals(InputType.TYPE_CLASS_TEXT, TextFocusKeyboard.inputTypeFor(TextFocusKeyboard.FOCUS_TEXT));
        // Focus leaving the field puts back what the stream always told the keyboard
        assertEquals(InputType.TYPE_CLASS_TEXT, TextFocusKeyboard.inputTypeFor(TextFocusKeyboard.FOCUS_NONE));
        assertEquals(InputType.TYPE_CLASS_TEXT, TextFocusKeyboard.inputTypeFor((byte) 42));
    }
}
