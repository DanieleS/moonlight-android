package com.limelight.stats;

import android.content.Context;
import android.content.res.Resources;
import android.text.format.DateFormat;

import com.limelight.R;
import com.limelight.nvstream.http.AppStats;
import com.limelight.nvstream.http.Iso8601;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The statistics' wording, in the reader's language: {@link StatsFormat}'s rules dressed in this
 * app's string resources. Dates follow the device's locale, so a month reads "ottobre 2026" in
 * Italian and "October 2026" in English, with the first letter capitalised only where a label
 * starts with it, as Italian wants.
 */
public class StatsText implements StatsFormat.Words {
    private final Context context;
    private final Resources res;
    private final Locale locale;

    public StatsText(Context context) {
        this.context = context;
        this.res = context.getResources();
        Locale configured = res.getConfiguration().locale;
        this.locale = configured != null ? configured : Locale.getDefault();
    }

    @Override
    public String minutes(long minutes) {
        return res.getString(R.string.stats_duration_minutes, (int) minutes);
    }

    @Override
    public String hours(long hours, long minutes) {
        return minutes == 0
                ? res.getString(R.string.stats_duration_hours, (int) hours)
                : res.getString(R.string.stats_duration_hours_minutes, (int) hours, (int) minutes);
    }

    public String duration(long seconds) {
        return StatsFormat.duration(seconds, this);
    }

    public String number(long n) {
        return NumberFormat.getIntegerInstance(locale).format(n);
    }

    public String hoursTotal(long seconds) {
        return res.getString(R.string.stats_hours_total, number(StatsFormat.roundHours(seconds)));
    }

    public Locale getLocale() {
        return locale;
    }

    /** "This week", "Last week", "28 Sep – 4 Oct", "October 2026" or "2026". */
    public String periodTitle(AppStats s) {
        Calendar from = Iso8601.parseDay(s.getFrom());
        if (from == null) {
            return "";
        }
        switch (s.getRange()) {
            case AppStats.RANGE_YEAR:
                return String.valueOf(from.get(Calendar.YEAR));
            case AppStats.RANGE_MONTH:
                return capitalize(format(from.getTime(), "MMMMyyyy"));
            default:
                if (s.getOffset() == 0) {
                    return res.getString(R.string.stats_this_week);
                }
                if (s.getOffset() == -1) {
                    return res.getString(R.string.stats_last_week);
                }
                Calendar to = Iso8601.parseDay(s.getTo());
                if (to == null) {
                    return format(from.getTime(), "dMMM");
                }
                to.add(Calendar.DAY_OF_MONTH, -1);
                return format(from.getTime(), "dMMM") + " – " + format(to.getTime(), "dMMM");
        }
    }

    /**
     * Compared with the period before — while this one is under way, with the period before up
     * to the same point. Null when that comparison would be noise (see {@link AppStats#isComparable()}).
     */
    public String delta(AppStats s) {
        if (!s.isComparable()) {
            return null;
        }
        boolean running = s.isRunning();
        int than;
        switch (s.getRange()) {
            case AppStats.RANGE_MONTH:
                than = running ? R.string.stats_than_month_running : R.string.stats_than_month_done;
                break;
            case AppStats.RANGE_YEAR:
                than = running ? R.string.stats_than_year_running : R.string.stats_than_year_done;
                break;
            default:
                than = running ? R.string.stats_than_week_running : R.string.stats_than_week_done;
                break;
        }
        String thanText = res.getString(than);
        long diff = s.getTotalSeconds() - s.getPreviousTotalSeconds();
        if (StatsFormat.isSame(s.getTotalSeconds(), s.getPreviousTotalSeconds())) {
            return res.getString(R.string.stats_same, thanText);
        }
        String signed = (diff > 0 ? "+" : "−") + duration(Math.abs(diff));
        return res.getString(R.string.stats_diff, signed, thanText);
    }

    /**
     * What to say instead of a period's figures: that GameActivity is missing, or that its
     * sessions start after this period.
     */
    public String untracked(AppStats s) {
        if (!s.hasActivity()) {
            return res.getString(R.string.stats_no_activity);
        }
        Calendar since = Iso8601.parseDay(s.getTrackingSince());
        if (since == null) {
            return res.getString(R.string.stats_untracked);
        }
        return res.getString(R.string.stats_untracked_since, format(since.getTime(), "dMMMM"));
    }

    /** The label under a bar: the weekday, the month's initial, or every fifth day of a month. */
    public String barLabel(String range, String date, int index) {
        Calendar day = Iso8601.parseDay(date);
        if (day == null) {
            return "";
        }
        if (AppStats.RANGE_WEEK.equals(range)) {
            String[] weekdays = res.getStringArray(R.array.stats_weekdays);
            return index >= 0 && index < weekdays.length ? weekdays[index] : "";
        }
        if (AppStats.RANGE_YEAR.equals(range)) {
            String[] months = res.getStringArray(R.array.stats_months);
            return months[day.get(Calendar.MONTH)];
        }
        // A month: every fifth day, so thirty labels don't collide.
        int d = day.get(Calendar.DAY_OF_MONTH);
        return d == 1 || d % 5 == 0 ? String.valueOf(d) : "";
    }

    /** "Wednesday 7 October · 2h 05m", or "October 2026 · 31h" for a year's month. */
    public String barCaption(String range, String date, long seconds) {
        Calendar day = Iso8601.parseDay(date);
        if (day == null) {
            return "";
        }
        String when = AppStats.RANGE_YEAR.equals(range)
                ? format(day.getTime(), "MMMMyyyy")
                : format(day.getTime(), "EEEEdMMMM");
        return res.getString(R.string.stats_bar_caption, capitalize(when), duration(seconds));
    }

    /** "October 2026", for when a game was last played. */
    public String monthYear(String iso) {
        long millis = Iso8601.parseMillis(iso);
        return millis == 0 ? "" : format(new Date(millis), "MMMMyyyy");
    }

    /**
     * "Today at 14:30", "Yesterday at 9:05", or the date (with the year once it is that old).
     * Lower-case in the middle of a sentence; only the relative words are lowered, so an English
     * month keeps its capital.
     */
    public String unlockedLabel(String iso, boolean lower) {
        long millis = Iso8601.parseMillis(iso);
        if (millis == 0) {
            return res.getString(R.string.stats_unlocked_label);
        }
        int days = StatsFormat.daysAgo(millis, System.currentTimeMillis());
        String time = android.text.format.DateFormat.getTimeFormat(context).format(new Date(millis));
        String relative = null;
        if (days == 0) {
            relative = res.getString(R.string.stats_today_at, time);
        } else if (days == 1) {
            relative = res.getString(R.string.stats_yesterday_at, time);
        }
        if (relative != null) {
            return lower ? relative.substring(0, 1).toLowerCase(locale) + relative.substring(1) : relative;
        }
        return date(millis, days > 300);
    }

    /** "7 October", or "7 October 2025" once it is that long ago. */
    public String date(long millis, boolean withYear) {
        return format(new Date(millis), withYear ? "dMMMMyyyy" : "dMMMM");
    }

    private String format(Date date, String skeleton) {
        String pattern = DateFormat.getBestDateTimePattern(locale, skeleton);
        return new SimpleDateFormat(pattern, locale).format(date);
    }

    /** Capitalises the first letter only: «Mercoledì 7 ottobre», not «7 Ottobre». */
    public String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.substring(0, 1).toUpperCase(locale) + text.substring(1);
    }
}
