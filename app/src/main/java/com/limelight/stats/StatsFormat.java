package com.limelight.stats;

import java.text.NumberFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * The arithmetic behind the statistics' wording, kept apart from the screens so it can be tested
 * on its own: how a duration is rounded, what a bar says above it, which rarity band an
 * achievement falls in, how far back a day is. The words themselves come from resources, through
 * {@link Words}, so English and Italian share one set of rules — the same ones CouchPilot uses.
 */
public final class StatsFormat {
    private StatsFormat() {
    }

    /** The two duration shapes, in the reader's language: "45 min", and "3h 05m" or "3h". */
    public interface Words {
        String minutes(long minutes);

        /** {@code minutes} is 0 for a round number of hours. */
        String hours(long hours, long minutes);
    }

    /** A length of time to the nearest minute: minutes under an hour, hours and minutes above. */
    public static String duration(long seconds, Words words) {
        long minutes = Math.round(Math.max(0, seconds) / 60.0);
        if (minutes < 60) {
            return words.minutes(minutes);
        }
        return words.hours(minutes / 60, minutes % 60);
    }

    /**
     * Above a week's bars, where there is room for little: "3:20", "45′", and nothing at all for
     * a day off.
     */
    public static String shortDuration(long seconds) {
        long minutes = Math.round(Math.max(0, seconds) / 60.0);
        if (minutes == 0) {
            return "";
        }
        if (minutes < 60) {
            return minutes + "′";
        }
        return (minutes / 60) + ":" + (minutes % 60 < 10 ? "0" : "") + (minutes % 60);
    }

    /** Whole hours, for the library's lifetime totals, where minutes would be noise. */
    public static long roundHours(long seconds) {
        return Math.round(Math.max(0, seconds) / 3600.0);
    }

    /** SuccessStory's own bands, for the few achievements that deserve a word. */
    public enum Rarity {
        ULTRA_RARE,
        RARE,
        UNCOMMON
    }

    /** The band for a share of players, or null when it is common or nobody knows. */
    public static Rarity rarity(Double percent) {
        if (percent == null) {
            return null;
        }
        if (percent < 2) {
            return Rarity.ULTRA_RARE;
        }
        if (percent < 10) {
            return Rarity.RARE;
        }
        if (percent < 30) {
            return Rarity.UNCOMMON;
        }
        return null;
    }

    /** "0.4", "7.5", "42": one decimal below 10%, where it still tells things apart. */
    public static String percent(double percent, Locale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        if (percent < 10) {
            format.setMaximumFractionDigits(1);
            format.setMinimumFractionDigits(0);
            return format.format(percent);
        }
        format.setMaximumFractionDigits(0);
        return format.format(Math.round(percent));
    }

    /**
     * How many calendar days ago an instant was, in the device's zone: 0 for today, 1 for
     * yesterday. Negative for the future, which a host clock running ahead can produce.
     */
    public static int daysAgo(long millis, long nowMillis) {
        return (int) Math.round((startOfDay(nowMillis) - startOfDay(millis)) / 86_400_000.0);
    }

    private static long startOfDay(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /** Below a minute either way, two periods count as the same. */
    public static boolean isSame(long seconds, long previousSeconds) {
        return Math.abs(seconds - previousSeconds) < 60;
    }

    /**
     * The next period range in the cycle week, month, year (step 1) or back (step -1), as the
     * X and Y buttons move through them.
     */
    public static String cycleRange(String range, int step) {
        String[] ranges = {"week", "month", "year"};
        int index = 0;
        for (int i = 0; i < ranges.length; i++) {
            if (ranges[i].equals(range)) {
                index = i;
            }
        }
        return ranges[Math.floorMod(index + step, ranges.length)];
    }

    /** One step along the periods, never into the future nor past what the host will answer. */
    public static int stepOffset(int offset, int step, int minOffset) {
        return Math.max(minOffset, Math.min(0, offset + step));
    }

    /** 0..1 of the longest, for the meters beside the top games; never a division by zero. */
    public static float share(long value, long max) {
        if (max <= 0) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, value / (float) max));
    }
}
