package com.limelight.companion;

/**
 * One action in the in-game menu when it is drawn on the companion panel. Just a label and
 * what to do — the panel supplies the row and the selection; the caller supplies these.
 */
public class CompanionMenuItem {
    public final CharSequence label;
    public final Runnable action;

    public CompanionMenuItem(CharSequence label, Runnable action) {
        this.label = label;
        this.action = action;
    }
}
