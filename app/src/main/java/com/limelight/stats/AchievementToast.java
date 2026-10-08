package com.limelight.stats;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.nvstream.http.Achievement;

import java.util.List;

/**
 * "Achievement unlocked": a small card raised over the stream, the companion panel or the library
 * for about eight seconds, as CouchPilot's toast — the newest unlock's icon and name, its game,
 * and how many more came with it. It never takes focus, so the game keeps the pad, and a newer
 * one replaces whatever is still showing rather than stacking.
 */
public final class AchievementToast {
    public static final long DURATION_MS = 8000;
    private static final long FADE_MS = 220;

    private AchievementToast() {
    }

    /**
     * Show the toast at the bottom centre of {@code parent}, which must be a FrameLayout (an
     * Activity's or a Dialog's content view is one).
     */
    public static void show(ViewGroup parent, List<Achievement> fresh, HostSession session) {
        if (parent == null || fresh == null || fresh.isEmpty()) {
            return;
        }
        Context context = parent.getContext();
        View old = parent.findViewById(R.id.achievementToast);
        if (old != null) {
            parent.removeView(old);
        }

        View toast = LayoutInflater.from(context).inflate(R.layout.achievement_toast, parent, false);
        toast.setId(R.id.achievementToast);
        Achievement first = fresh.get(0);
        int more = fresh.size() - 1;
        String title = context.getString(R.string.achievement_toast_title);
        if (more > 0) {
            title = context.getString(R.string.achievement_toast_title_more, title,
                    context.getResources().getQuantityString(R.plurals.achievement_toast_more, more, more));
        }
        ((TextView) toast.findViewById(R.id.achievementToastTitle)).setText(title);
        ((TextView) toast.findViewById(R.id.achievementToastDetail)).setText(
                first.getGame().isEmpty() ? first.getName()
                        : context.getString(R.string.stats_achievement_detail, first.getName(), first.getGame()));
        ImageView icon = toast.findViewById(R.id.achievementToastIcon);
        icon.setClipToOutline(true);
        AchievementIcons.load(icon, first, session);

        int margin = Math.round(24 * context.getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = margin;
        lp.leftMargin = margin;
        lp.rightMargin = margin;
        parent.addView(toast, lp);

        toast.setAlpha(0f);
        toast.setTranslationY(margin);
        toast.animate().alpha(1f).translationY(0).setDuration(FADE_MS).start();
        toast.postDelayed(() -> toast.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction(() -> parent.removeView(toast)).start(), DURATION_MS);
    }
}
