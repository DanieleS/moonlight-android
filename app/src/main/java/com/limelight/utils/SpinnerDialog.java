package com.limelight.utils;

import java.util.ArrayList;
import java.util.Iterator;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.limelight.R;

public class SpinnerDialog implements Runnable,OnCancelListener {
    private final String title;
    private final String message;
    private final Activity activity;
    private AlertDialog dialog;
    private TextView messageView;
    private final boolean finish;

    private static final ArrayList<SpinnerDialog> rundownDialogs = new ArrayList<>();

    private SpinnerDialog(Activity activity, String title, String message, boolean finish)
    {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.dialog = null;
        this.finish = finish;
    }

    public static SpinnerDialog displayDialog(Activity activity, String title, String message, boolean finish)
    {
        SpinnerDialog spinner = new SpinnerDialog(activity, title, message, finish);
        activity.runOnUiThread(spinner);
        return spinner;
    }

    public static void closeDialogs(Activity activity)
    {
        synchronized (rundownDialogs) {
            Iterator<SpinnerDialog> i = rundownDialogs.iterator();
            while (i.hasNext()) {
                SpinnerDialog dialog = i.next();
                if (dialog.activity == activity) {
                    i.remove();
                    if (dialog.dialog != null && dialog.dialog.isShowing()) {
                        dialog.dialog.dismiss();
                    }
                }
            }
        }
    }

    public void dismiss()
    {
        // Running again with dialog != null will destroy it
        activity.runOnUiThread(this);
    }

    public void setMessage(final String message)
    {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (messageView != null) {
                    messageView.setText(message);
                }
            }
        });
    }

    private int dp(float value)
    {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics());
    }

    // A spinner beside its message, laid out as the dialog's content view. The Material
    // indicator carries the signal colour explicitly, so it stays on-brand even in the
    // stream's context, which does not map the Material colour roles.
    private LinearLayout buildContent()
    {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), dp(8), dp(24), dp(8));

        CircularProgressIndicator spinner = new CircularProgressIndicator(activity);
        spinner.setIndeterminate(true);
        spinner.setIndicatorSize(dp(36));
        spinner.setTrackThickness(dp(3));
        spinner.setIndicatorColor(ContextCompat.getColor(activity, R.color.signal));
        spinner.setTrackColor(ContextCompat.getColor(activity, R.color.divider));

        messageView = new TextView(activity);
        messageView.setText(message);
        messageView.setTextColor(ContextCompat.getColor(activity, R.color.content_secondary));
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);

        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginStart(dp(20));
        row.addView(spinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(messageView, textParams);

        return row;
    }

    @Override
    public void run() {

        // If we're dying, don't bother doing anything
        if (activity.isFinishing()) {
            return;
        }

        if (dialog == null)
        {
            MaterialAlertDialogBuilder builder =
                    new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_Ratatoskr_MaterialAlertDialog);

            if (!TextUtils.isEmpty(title)) {
                builder.setTitle(title);
            }
            builder.setView(buildContent());
            builder.setOnCancelListener(this);

            // If we want to finish the activity when this is killed, make it cancellable
            builder.setCancelable(finish);

            dialog = builder.create();
            dialog.setCanceledOnTouchOutside(false);

            synchronized (rundownDialogs) {
                rundownDialogs.add(this);
                dialog.show();
            }
        }
        else
        {
            synchronized (rundownDialogs) {
                if (rundownDialogs.remove(this) && dialog.isShowing()) {
                    dialog.dismiss();
                }
            }
        }
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        synchronized (rundownDialogs) {
            rundownDialogs.remove(this);
        }

        // This will only be called if finish was true, so we don't need to check again
        activity.finish();
    }
}
