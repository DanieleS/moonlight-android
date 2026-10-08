package com.limelight.stats;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.http.Achievement;
import com.limelight.nvstream.http.AppStats;
import com.limelight.utils.UiHelper;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * How much is played on a PC, a week, a month or a year at a time, then the library at large:
 * CouchPilot's statistics page, served by Vibepollo's {@code /appstats}.
 *
 * <p>Built for a gamepad first. The bumpers step the period from anywhere on the page, X and Y
 * cycle week, month and year, and the d-pad walks one column top to bottom: the range buttons,
 * the period (left and right step it there too), the chart (left and right pick a bar), the most
 * played, the latest unlocks and the games worth picking up again. Each game opens its own
 * statistics and achievements sheet. B leaves, as it does everywhere.
 */
public class StatsActivity extends AppCompatActivity {

    /** Open the statistics of the host {@code link} points at. */
    public static void start(Context context, HostLink link) {
        Intent intent = new Intent(context, StatsActivity.class);
        link.putInto(intent);
        context.startActivity(intent);
    }

    private HostLink link;
    private HostSession session;
    private CoverLoader covers;
    private StatsText text;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    private String range = AppStats.RANGE_WEEK;
    private int offset;
    private int request;
    private AppStats stats;

    private TextView[] rangeButtons;
    private View statusView;
    private View statusProgress;
    private View statusTitle;
    private View statusHint;
    private View bodyView;
    private View periodNav;
    private ImageView previousView;
    private ImageView nextView;
    private TextView periodTitle;
    private TextView untrackedView;
    private View trackedView;
    private TextView totalView;
    private TextView deltaView;
    private TextView sessionsView;
    private TextView averageView;
    private TextView captionView;
    private StatsBarsView chart;
    private View topSection;
    private LinearLayout topList;
    private View achievementsSection;
    private TextView achievementsCount;
    private TextView achievementsCountLabel;
    private LinearLayout achievementsList;
    private View achievementsEmpty;
    private TextView libraryHours;
    private TextView libraryGames;
    private TextView libraryGamesLabel;
    private TextView libraryNeverPlayed;
    private TextView libraryThisYear;
    private View resumeSection;
    private LinearLayout resumeList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiHelper.setLocale(this);

        link = HostLink.from(getIntent());
        if (link == null) {
            finish();
            return;
        }
        session = new HostSession(this, link);
        covers = new CoverLoader(this, session, link.getPcUuid());
        text = new StatsText(this);

        setContentView(R.layout.activity_stats);
        enterImmersive();

        TextView title = findViewById(R.id.statsTitle);
        if (link.getPcName() != null) {
            title.setText(getString(R.string.stats_title) + " · " + link.getPcName());
        }
        findViewById(R.id.statsBackButton).setOnClickListener(v -> finish());

        rangeButtons = new TextView[]{
                findViewById(R.id.statsRangeWeek),
                findViewById(R.id.statsRangeMonth),
                findViewById(R.id.statsRangeYear)};
        final String[] ranges = {AppStats.RANGE_WEEK, AppStats.RANGE_MONTH, AppStats.RANGE_YEAR};
        for (int i = 0; i < rangeButtons.length; i++) {
            final String r = ranges[i];
            rangeButtons[i].setOnClickListener(v -> setRange(r));
        }

        statusView = findViewById(R.id.statsStatus);
        statusProgress = findViewById(R.id.statsStatusProgress);
        statusTitle = findViewById(R.id.statsStatusTitle);
        statusHint = findViewById(R.id.statsStatusHint);
        statusView.setOnClickListener(v -> load());
        bodyView = findViewById(R.id.statsBody);

        periodNav = findViewById(R.id.statsPeriodNav);
        previousView = findViewById(R.id.statsPrevious);
        nextView = findViewById(R.id.statsNext);
        periodTitle = findViewById(R.id.statsPeriodTitle);
        previousView.setOnClickListener(v -> stepPeriod(-1));
        nextView.setOnClickListener(v -> stepPeriod(1));

        untrackedView = findViewById(R.id.statsUntracked);
        trackedView = findViewById(R.id.statsTracked);
        totalView = findViewById(R.id.statsTotal);
        deltaView = findViewById(R.id.statsDelta);
        sessionsView = findViewById(R.id.statsSessions);
        averageView = findViewById(R.id.statsAverage);
        captionView = findViewById(R.id.statsBarCaption);
        chart = findViewById(R.id.statsChart);
        chart.setOnSelectionChangedListener(this::updateCaption);

        topSection = findViewById(R.id.statsTopSection);
        topList = findViewById(R.id.statsTopList);
        achievementsSection = findViewById(R.id.statsAchievementsSection);
        achievementsCount = findViewById(R.id.statsAchievementsCount);
        achievementsCountLabel = findViewById(R.id.statsAchievementsCountLabel);
        achievementsList = findViewById(R.id.statsAchievementsList);
        achievementsEmpty = findViewById(R.id.statsAchievementsEmpty);
        libraryHours = findViewById(R.id.statsLibraryHours);
        libraryGames = findViewById(R.id.statsLibraryGames);
        libraryGamesLabel = findViewById(R.id.statsLibraryGamesLabel);
        libraryNeverPlayed = findViewById(R.id.statsLibraryNeverPlayed);
        libraryThisYear = findViewById(R.id.statsLibraryThisYear);
        resumeSection = findViewById(R.id.statsResumeSection);
        resumeList = findViewById(R.id.statsResumeList);

        applyColumnWidth();
        refreshRangeButtons();
        statusView.requestFocus();
        load();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyColumnWidth();
    }

    // One centred column, no wider than reads comfortably, on however wide a screen.
    private void applyColumnWidth() {
        View content = findViewById(R.id.statsContent);
        int maxWidth = getResources().getDimensionPixelSize(R.dimen.stats_max_width);
        int gutter = getResources().getDimensionPixelSize(R.dimen.stats_gutter);
        int side = Math.max(gutter, (getResources().getDisplayMetrics().widthPixels - maxWidth) / 2);
        content.setPadding(side, content.getPaddingTop(), side, content.getPaddingBottom());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loader.shutdownNow();
        if (covers != null) {
            covers.shutdown();
        }
    }

    // The library is a full-screen console surface; so is this page, opened from it.
    private void enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
        View root = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            v.setPadding(0, 0, 0, 0);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersive();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            boolean first = event.getRepeatCount() == 0;
            if (code == KeyEvent.KEYCODE_BUTTON_L1 && first) {
                stepPeriod(-1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_R1 && first) {
                stepPeriod(1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_X && first) {
                setRange(StatsFormat.cycleRange(range, -1));
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_Y && first) {
                setRange(StatsFormat.cycleRange(range, 1));
                return true;
            }
            // On the period itself, left and right step it, like a spinner.
            if (periodNav.hasFocus()) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    stepPeriod(-1);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    stepPeriod(1);
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void setRange(String newRange) {
        if (newRange.equals(range)) {
            return;
        }
        range = newRange;
        // A new range starts from the period under way, as CouchPilot does.
        offset = 0;
        refreshRangeButtons();
        load();
    }

    private void stepPeriod(int step) {
        int next = StatsFormat.stepOffset(offset, step, AppStats.MIN_OFFSET);
        if (next == offset) {
            return;
        }
        offset = next;
        load();
    }

    private void refreshRangeButtons() {
        String[] ranges = {AppStats.RANGE_WEEK, AppStats.RANGE_MONTH, AppStats.RANGE_YEAR};
        for (int i = 0; i < rangeButtons.length; i++) {
            rangeButtons[i].setSelected(ranges[i].equals(range));
        }
    }

    // Only the latest request's answer is shown: stepping quickly through periods fires several,
    // and they need not come back in order.
    private void load() {
        final int mine = ++request;
        final String r = range;
        final int o = offset;
        if (stats == null) {
            showStatus(true);
        }
        loader.execute(() -> {
            AppStats result = null;
            Exception failure = null;
            try {
                result = session.http().getAppStats(r, o);
            } catch (Exception e) {
                LimeLog.warning("Stats: " + e.getMessage());
                failure = e;
            }
            final AppStats loaded = result;
            final boolean failed = failure != null || result == null;
            runOnUiThread(() -> {
                if (isFinishing() || mine != request) {
                    return;
                }
                if (failed) {
                    if (stats == null) {
                        showStatus(false);
                    } else {
                        Toast.makeText(this, R.string.stats_failed_title, Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                bind(loaded);
            });
        });
    }

    private void showStatus(boolean loading) {
        boolean hadFocus = bodyView.hasFocus();
        statusView.setVisibility(View.VISIBLE);
        bodyView.setVisibility(View.GONE);
        statusProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        statusTitle.setVisibility(loading ? View.GONE : View.VISIBLE);
        statusHint.setVisibility(loading ? View.GONE : View.VISIBLE);
        if (hadFocus || getCurrentFocus() == null) {
            statusView.requestFocus();
        }
    }

    private void bind(AppStats s) {
        boolean firstBind = stats == null;
        FocusMemo memo = FocusMemo.capture(getCurrentFocus(), topList, achievementsList, resumeList);
        stats = s;

        statusView.setVisibility(View.GONE);
        bodyView.setVisibility(View.VISIBLE);

        periodTitle.setText(text.periodTitle(s));
        previousView.setAlpha(offset <= AppStats.MIN_OFFSET ? 0.25f : 1f);
        nextView.setAlpha(offset >= 0 ? 0.25f : 1f);

        // Before the log started there is nothing to show by day; say so instead of zeros.
        boolean tracked = s.isTracked();
        untrackedView.setVisibility(tracked ? View.GONE : View.VISIBLE);
        trackedView.setVisibility(tracked ? View.VISIBLE : View.GONE);
        if (!tracked) {
            untrackedView.setText(text.untracked(s));
        } else {
            totalView.setText(text.duration(s.getTotalSeconds()));
            String delta = text.delta(s);
            deltaView.setText(delta);
            deltaView.setVisibility(delta == null ? View.GONE : View.VISIBLE);
            sessionsView.setText(getResources().getQuantityString(R.plurals.stats_sessions,
                    s.getSessions(), s.getSessions()));
            if (s.getSessions() > 0) {
                averageView.setVisibility(View.VISIBLE);
                averageView.setText(getString(R.string.stats_average,
                        text.duration(s.getTotalSeconds() / s.getSessions())));
            } else {
                averageView.setVisibility(View.GONE);
            }
            bindChart(s);
        }

        bindTop(s);
        bindAchievements(s);
        bindLibrary(s);
        bindResume(s);

        if (firstBind) {
            // Land on the period, where left and right already do something.
            periodNav.requestFocus();
        } else {
            memo.restore(periodNav);
        }
        // The chart goes away for a period before the log began; don't leave the pad nowhere.
        View focused = getCurrentFocus();
        if (focused == null || !focused.isShown()) {
            periodNav.requestFocus();
        }
    }

    private void bindChart(AppStats s) {
        List<AppStats.Bucket> buckets = s.getBuckets();
        int n = buckets.size();
        long[] values = new long[n];
        String[] labels = new String[n];
        String[] valueLabels = AppStats.RANGE_WEEK.equals(s.getRange()) ? new String[n] : null;
        int todayIndex = -1;
        String today = s.getToday();
        for (int i = 0; i < n; i++) {
            AppStats.Bucket b = buckets.get(i);
            values[i] = b.getSeconds();
            labels[i] = text.barLabel(s.getRange(), b.getDate(), i);
            if (valueLabels != null) {
                valueLabels[i] = StatsFormat.shortDuration(b.getSeconds());
            }
            boolean isToday = AppStats.RANGE_YEAR.equals(s.getRange())
                    ? b.getDate().length() >= 7 && today.length() >= 7
                            && b.getDate().substring(0, 7).equals(today.substring(0, 7))
                    : b.getDate().equals(today);
            if (isToday) {
                todayIndex = i;
            }
        }
        chart.setData(values, labels, valueLabels, todayIndex);
    }

    private void updateCaption(int index) {
        if (stats == null || index < 0 || index >= stats.getBuckets().size()) {
            captionView.setText(R.string.stats_bars_hint);
            return;
        }
        AppStats.Bucket b = stats.getBuckets().get(index);
        captionView.setText(text.barCaption(stats.getRange(), b.getDate(), b.getSeconds()));
    }

    private void bindTop(AppStats s) {
        topList.removeAllViews();
        List<AppStats.TopGame> top = s.getTop();
        topSection.setVisibility(top.isEmpty() ? View.GONE : View.VISIBLE);
        long max = 1;
        for (AppStats.TopGame g : top) {
            max = Math.max(max, g.getSeconds());
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (AppStats.TopGame g : top) {
            View row = inflater.inflate(R.layout.stats_top_row, topList, false);
            ((TextView) row.findViewById(R.id.statsRowName)).setText(g.getName());
            ((TextView) row.findViewById(R.id.statsRowValue)).setText(text.duration(g.getSeconds()));
            View meter = row.findViewById(R.id.statsRowMeter);
            meter.setPivotX(0);
            meter.setScaleX(StatsFormat.share(g.getSeconds(), max));
            covers.load(row.findViewById(R.id.statsRowCover), g.getUuid());
            row.setOnClickListener(v -> openGame(g.getUuid(), g.getName()));
            topList.addView(row);
        }
    }

    private void bindAchievements(AppStats s) {
        AppStats.Achievements a = s.getAchievements();
        achievementsList.removeAllViews();
        if (a == null) {
            achievementsSection.setVisibility(View.GONE);
            return;
        }
        achievementsSection.setVisibility(View.VISIBLE);
        achievementsCount.setText(text.number(a.getUnlocked()));
        achievementsCountLabel.setText(getResources().getQuantityString(R.plurals.stats_unlocked, a.getUnlocked()));
        achievementsEmpty.setVisibility(a.getRecent().isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Achievement item : a.getRecent()) {
            View row = inflater.inflate(R.layout.stats_achievement_row, achievementsList, false);
            ((TextView) row.findViewById(R.id.statsAchievementName)).setText(item.getName());
            ((TextView) row.findViewById(R.id.statsAchievementDetail)).setText(getString(
                    R.string.stats_achievement_detail, item.getGame(), text.unlockedLabel(item.getUnlockedAt(), false)));
            ImageView icon = row.findViewById(R.id.statsAchievementIcon);
            icon.setClipToOutline(true);
            AchievementIcons.load(icon, item, session);
            row.setOnClickListener(v -> openGame(item.getAppUuid(), item.getGame()));
            achievementsList.addView(row);
        }
    }

    private void bindLibrary(AppStats s) {
        AppStats.Library lib = s.getLibrary();
        libraryHours.setText(text.hoursTotal(lib.getPlaytimeSeconds()));
        libraryGames.setText(text.number(lib.getGames()));
        libraryGamesLabel.setText(getString(R.string.stats_games, text.number(lib.getInstalled())));
        libraryNeverPlayed.setText(text.number(lib.getInstalledNeverPlayed()));
        libraryThisYear.setText(text.number(lib.getPlayedThisYear()));
    }

    private void bindResume(AppStats s) {
        resumeList.removeAllViews();
        List<AppStats.ResumeGame> resume = s.getResume();
        resumeSection.setVisibility(resume.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (AppStats.ResumeGame g : resume) {
            View item = inflater.inflate(R.layout.stats_resume_item, resumeList, false);
            ((TextView) item.findViewById(R.id.statsResumeName)).setText(g.getName());
            ((TextView) item.findViewById(R.id.statsResumeDetail)).setText(getString(R.string.stats_resume_detail,
                    text.hoursTotal(g.getPlaytimeSeconds()), text.monthYear(g.getLastActivity())));
            covers.load(item.findViewById(R.id.statsResumeCover), g.getUuid());
            item.setOnClickListener(v -> openGame(g.getUuid(), g.getName()));
            resumeList.addView(item);
        }
    }

    private void openGame(String uuid, String name) {
        if (uuid == null || uuid.isEmpty()) {
            return;
        }
        GameStatsSheet.show(this, session, uuid, name);
    }

    /**
     * Where the focus was among rows that a new period rebuilds, so it can land on the same
     * place in the new rows rather than fall back to wherever the framework puts it.
     */
    private static final class FocusMemo {
        private final ViewGroup container;
        private final int index;

        private FocusMemo(ViewGroup container, int index) {
            this.container = container;
            this.index = index;
        }

        static FocusMemo capture(View focused, ViewGroup... containers) {
            if (focused != null) {
                for (ViewGroup container : containers) {
                    int index = container.indexOfChild(focused);
                    if (index >= 0) {
                        return new FocusMemo(container, index);
                    }
                }
            }
            return new FocusMemo(null, -1);
        }

        void restore(View fallback) {
            if (container == null) {
                return;
            }
            int count = container.getChildCount();
            if (count > 0 && container.isShown()) {
                container.getChildAt(Math.min(index, count - 1)).requestFocus();
            } else {
                fallback.requestFocus();
            }
        }
    }
}
