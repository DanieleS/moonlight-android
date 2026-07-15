package com.limelight.ui;

import android.app.Dialog;
import android.content.Context;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

import java.util.ArrayList;
import java.util.List;

/**
 * A menu drawn on the app's own raised surface, with the same focus grammar as the tiles.
 * Build it up with a title and a list of actions, then present it as a panel centred on the
 * screen and focused for a gamepad — the in-game menu and the library's per-game menu both
 * wear it.
 */
public class MenuSheet {

    /** The fraction of the screen a menu may fill before it starts scrolling. */
    private static final float MAX_HEIGHT_FRACTION = 0.78f;

    public static final class Item {
        final CharSequence label;
        final int iconRes;   // 0 for none
        final boolean checked;
        final Runnable action;

        Item(CharSequence label, int iconRes, boolean checked, Runnable action) {
            this.label = label;
            this.iconRes = iconRes;
            this.checked = checked;
            this.action = action;
        }
    }

    private final Context context;
    private CharSequence title;
    private final List<Item> items = new ArrayList<>();

    public MenuSheet(Context context) {
        this.context = context;
    }

    public MenuSheet setTitle(CharSequence title) {
        this.title = title;
        return this;
    }

    public MenuSheet add(CharSequence label, Runnable action) {
        items.add(new Item(label, 0, false, action));
        return this;
    }

    public MenuSheet add(CharSequence label, int iconRes, Runnable action) {
        items.add(new Item(label, iconRes, false, action));
        return this;
    }

    public MenuSheet addChecked(CharSequence label, boolean checked, Runnable action) {
        items.add(new Item(label, 0, checked, action));
        return this;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** Sit centred over a game, focused for a gamepad. */
    public Dialog showCentered(Context ctx) {
        Dialog dialog = new Dialog(ctx, R.style.MenuSheetDialog);
        LayoutInflater inflater = LayoutInflater.from(dialog.getContext());

        View panel = buildPanel(inflater, null, dialog::dismiss);
        dialog.setContentView(panel);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = Math.min(widthCap(), (int) (screenWidth() * 0.92f));
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            window.setAttributes(lp);
            window.setDimAmount(0.6f);
        }

        dialog.show();

        // Land the gamepad on the first action.
        LinearLayout container = panel.findViewById(R.id.menuSheetItems);
        if (container.getChildCount() > 0) {
            container.getChildAt(0).requestFocus();
        }
        return dialog;
    }

    private View buildPanel(LayoutInflater inflater, ViewGroup attachTo, Runnable dismiss) {
        View panel = inflater.inflate(R.layout.menu_sheet, attachTo, false);

        TextView titleView = panel.findViewById(R.id.menuSheetTitle);
        if (TextUtils.isEmpty(title)) {
            titleView.setVisibility(View.GONE);
        } else {
            titleView.setText(title);
        }

        LinearLayout container = panel.findViewById(R.id.menuSheetItems);
        for (Item item : items) {
            container.addView(buildRow(inflater, container, item, dismiss));
        }

        MaxHeightScrollView scroll = panel.findViewById(R.id.menuSheetScroll);
        scroll.setMaxHeightPx((int) (screenHeight() * MAX_HEIGHT_FRACTION));
        return panel;
    }

    private View buildRow(LayoutInflater inflater, ViewGroup container, Item item, Runnable dismiss) {
        View row = inflater.inflate(R.layout.menu_sheet_row, container, false);

        ((TextView) row.findViewById(R.id.menuRowLabel)).setText(item.label);

        if (item.iconRes != 0) {
            ImageView icon = row.findViewById(R.id.menuRowIcon);
            icon.setImageResource(item.iconRes);
            icon.setVisibility(View.VISIBLE);
        }
        if (item.checked) {
            row.findViewById(R.id.menuRowCheck).setVisibility(View.VISIBLE);
        }

        final Runnable action = item.action;
        row.setOnClickListener(v -> {
            dismiss.run();
            if (action != null) {
                action.run();
            }
        });
        return row;
    }

    private int widthCap() {
        return context.getResources().getDimensionPixelSize(R.dimen.menu_sheet_max_width);
    }

    private int screenWidth() {
        return metrics().widthPixels;
    }

    private int screenHeight() {
        return metrics().heightPixels;
    }

    private DisplayMetrics metrics() {
        return context.getResources().getDisplayMetrics();
    }

    private int dp(int value) {
        return Math.round(value * metrics().density);
    }
}
