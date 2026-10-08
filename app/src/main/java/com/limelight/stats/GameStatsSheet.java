package com.limelight.stats;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.http.Achievement;
import com.limelight.nvstream.http.GameAchievements;
import com.limelight.nvstream.http.GameStats;
import com.limelight.nvstream.http.Iso8601;
import com.limelight.ui.MaxHeightScrollView;

import java.util.ArrayList;
import java.util.List;

/**
 * One game's statistics and achievements, raised over the library or the statistics page:
 * CouchPilot's game sheet, minus what Ratatoskr already offers elsewhere (playing, installing).
 *
 * <p>The play figures come from {@code /appstats?appuuid=} and the achievements from
 * {@code /appachievements}; either can be missing (a game SuccessStory doesn't follow, a game
 * GameActivity has no session of) and the sheet shows whichever there is. Sessions only exist
 * where GameActivity recorded them, while Playnite's totals always do, so the figures fall back
 * to launches, without the sparkline, when there are no sessions to count, as CouchPilot's do.
 */
public final class GameStatsSheet {
    private static final int LATEST_ICONS = 5;
    private static final float MAX_HEIGHT_FRACTION = 0.86f;

    private GameStatsSheet() {
    }

    public static Dialog show(Activity activity, HostSession session, String appUuid, String name) {
        Dialog dialog = new Dialog(activity, R.style.MenuSheetDialog);
        View panel = LayoutInflater.from(dialog.getContext()).inflate(R.layout.game_stats_sheet, null, false);
        dialog.setContentView(panel);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = window.getAttributes();
            int cap = activity.getResources().getDimensionPixelSize(R.dimen.stats_sheet_max_width);
            lp.width = Math.min(cap, (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f));
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            window.setAttributes(lp);
            window.setDimAmount(0.6f);
        }

        ((TextView) panel.findViewById(R.id.gameStatsTitle)).setText(name);
        MaxHeightScrollView scroll = panel.findViewById(R.id.gameStatsScroll);
        scroll.setMaxHeightPx((int) (activity.getResources().getDisplayMetrics().heightPixels * MAX_HEIGHT_FRACTION));
        // Something has to hold the pad while there is nothing to select, or the d-pad has
        // nowhere to scroll from; the scroll view stands in until the achievements card arrives.
        scroll.setFocusable(true);

        dialog.show();
        scroll.requestFocus();

        new Thread(() -> {
            GameStats stats = null;
            GameAchievements achievements = null;
            boolean failed = false;
            try {
                stats = session.http().getGameStats(appUuid);
            } catch (Exception e) {
                LimeLog.warning("Game stats: " + e.getMessage());
                failed = true;
            }
            try {
                achievements = session.http().getAppAchievements(appUuid);
            } catch (Exception e) {
                LimeLog.warning("Game achievements: " + e.getMessage());
                failed = true;
            }
            final GameStats s = stats;
            final GameAchievements a = achievements;
            final boolean f = failed;
            activity.runOnUiThread(() -> {
                if (!dialog.isShowing() || activity.isFinishing()) {
                    return;
                }
                bind(activity, panel, session, name, s, a, f);
            });
        }, "GameStatsSheet").start();

        return dialog;
    }

    private static void bind(Activity activity, View panel, HostSession session, String name,
                             GameStats stats, GameAchievements achievements, boolean failed) {
        StatsText text = new StatsText(activity);
        TextView status = panel.findViewById(R.id.gameStatsStatus);
        boolean hasPlay = stats != null && stats.getPlaytimeSeconds() > 0;
        boolean hasAchievements = achievements != null && achievements.getTotal() > 0;

        if (hasPlay || hasAchievements) {
            status.setVisibility(View.GONE);
        } else {
            status.setText(failed ? R.string.game_stats_failed : R.string.game_stats_nothing);
        }

        if (hasPlay) {
            bindPlay(activity, panel, text, stats);
        }
        if (hasAchievements) {
            bindAchievements(activity, panel, session, text, name, achievements);
        }
    }

    private static void bindPlay(Activity activity, View panel, StatsText text, GameStats s) {
        panel.findViewById(R.id.gameStatsPlay).setVisibility(View.VISIBLE);
        ((TextView) panel.findViewById(R.id.gameStatsTotal)).setText(text.duration(s.getPlaytimeSeconds()));

        TextView count = panel.findViewById(R.id.gameStatsCount);
        TextView countLabel = panel.findViewById(R.id.gameStatsCountLabel);
        TextView average = panel.findViewById(R.id.gameStatsAverage);
        // Without GameActivity the host reports no sessions; don't trust a stray count either.
        boolean hasSessions = s.hasActivity() && s.getSessions() > 0;
        TextView averageLabel = panel.findViewById(R.id.gameStatsAverageLabel);
        View averageBlock = panel.findViewById(R.id.gameStatsAverageBlock);
        if (hasSessions) {
            count.setText(text.number(s.getSessions()));
            countLabel.setText(activity.getResources().getQuantityString(R.plurals.game_stats_sessions, s.getSessions()));
            average.setText(text.duration(s.getAverageSeconds()));
            averageLabel.setText(R.string.game_stats_per_session);
        } else {
            count.setText(text.number(s.getPlayCount()));
            countLabel.setText(R.string.game_stats_launches);
            if (s.getPlayCount() > 0) {
                average.setText(text.duration(s.getPlaytimeSeconds() / s.getPlayCount()));
                averageLabel.setText(R.string.game_stats_per_launch);
            } else {
                averageBlock.setVisibility(View.INVISIBLE);
            }
        }

        View weeks = panel.findViewById(R.id.gameStatsWeeks);
        if (hasSessions) {
            long[] values = s.getWeeks();
            ((TextView) panel.findViewById(R.id.gameStatsThisWeek)).setText(
                    activity.getString(R.string.game_stats_this_week, text.duration(values[values.length - 1])));
            StatsBarsView spark = panel.findViewById(R.id.gameStatsSpark);
            spark.setCompact(true);
            spark.setData(values, null, null, values.length - 1);
        } else {
            weeks.setVisibility(View.GONE);
        }

        // The last session GameActivity has, or Playnite's last activity when it has none.
        TextView last = panel.findViewById(R.id.gameStatsLast);
        boolean lastSession = s.hasActivity() && s.hasLastSession();
        String when = lastSession ? s.getLastSessionStart() : s.getLastActivity();
        long millis = Iso8601.parseMillis(when);
        if (millis == 0) {
            last.setVisibility(View.GONE);
        } else {
            String day = lastPlayedDay(activity, text, millis);
            last.setText(lastSession
                    ? activity.getString(R.string.game_stats_last_played_for, day, text.duration(s.getLastSessionSeconds()))
                    : activity.getString(R.string.game_stats_last_played, day));
        }
    }

    private static String lastPlayedDay(Activity activity, StatsText text, long millis) {
        int days = StatsFormat.daysAgo(millis, System.currentTimeMillis());
        if (days <= 0) {
            return activity.getString(R.string.game_stats_today);
        }
        if (days == 1) {
            return activity.getString(R.string.game_stats_yesterday);
        }
        if (days < 7) {
            return activity.getResources().getQuantityString(R.plurals.game_stats_days_ago, days, days);
        }
        return text.date(millis, days > 300);
    }

    private static void bindAchievements(Activity activity, View panel, HostSession session,
                                         StatsText text, String name, GameAchievements a) {
        panel.findViewById(R.id.gameStatsAchievements).setVisibility(View.VISIBLE);
        ((TextView) panel.findViewById(R.id.gameStatsAchievementsCount)).setText(
                activity.getString(R.string.game_stats_achievements_count, a.getUnlocked(), a.getTotal()));
        View meter = panel.findViewById(R.id.gameStatsAchievementsMeter);
        meter.setPivotX(0);
        meter.setScaleX(a.getPercent() / 100f);
        ((TextView) panel.findViewById(R.id.gameStatsAchievementsPercent)).setText(
                activity.getString(R.string.achievements_percent, a.getPercent()));

        List<Achievement> latest = new ArrayList<>();
        for (Achievement item : a.getItems()) {
            if (item.isUnlocked() && latest.size() < LATEST_ICONS) {
                latest.add(item);
            }
        }
        ((TextView) panel.findViewById(R.id.gameStatsAchievementsLatest)).setText(latest.isEmpty()
                ? activity.getString(R.string.game_stats_none_yet)
                : activity.getString(R.string.game_stats_latest, latest.get(0).getName()));

        LinearLayout icons = panel.findViewById(R.id.gameStatsAchievementsIcons);
        icons.removeAllViews();
        int size = Math.round(40 * activity.getResources().getDisplayMetrics().density);
        int gap = Math.round(6 * activity.getResources().getDisplayMetrics().density);
        if (latest.isEmpty()) {
            latest.add(null);
        }
        for (Achievement item : latest) {
            ImageView icon = new ImageView(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            icon.setLayoutParams(lp);
            icon.setBackgroundResource(R.drawable.stats_icon_bg);
            icon.setClipToOutline(true);
            if (item == null) {
                icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                icon.setImageResource(R.drawable.ic_trophy);
            } else {
                AchievementIcons.load(icon, item, session);
            }
            icons.addView(icon);
        }

        View card = panel.findViewById(R.id.gameStatsAchievementsCard);
        card.setOnClickListener(v -> AchievementsListDialog.show(activity, session, name, a));
        // The one thing to do here; give it the pad.
        card.requestFocus();
    }
}
