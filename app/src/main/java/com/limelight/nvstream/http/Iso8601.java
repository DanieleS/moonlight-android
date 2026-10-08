package com.limelight.nvstream.http;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * The two date shapes the host's statistics speak, read without java.time (this app still runs on
 * API 21 without desugaring).
 *
 * <ul>
 *   <li>Instants, ISO8601 with a {@code Z} or a numeric offset and any number of fraction digits,
 *       as Playnite and SuccessStory write them: {@code 2026-10-07T14:30:00.1234567Z}.</li>
 *   <li>Days, {@code yyyy-MM-dd}, which name a day of play in the PC's own calendar (5:00 to
 *       5:00) and so must be read as local dates, never shifted through UTC.</li>
 * </ul>
 */
public final class Iso8601 {
    private Iso8601() {
    }

    /**
     * An instant in milliseconds since the epoch, or 0 when there is nothing to read. A value
     * without an offset is taken as UTC, which is how the host writes every instant.
     */
    public static long parseMillis(String iso) {
        if (iso == null || iso.length() < 10) {
            return 0;
        }
        try {
            int year = Integer.parseInt(iso.substring(0, 4));
            int month = Integer.parseInt(iso.substring(5, 7));
            int day = Integer.parseInt(iso.substring(8, 10));
            int hour = 0, minute = 0, second = 0, millis = 0;
            long offsetMillis = 0;
            if (iso.length() >= 19 && (iso.charAt(10) == 'T' || iso.charAt(10) == ' ')) {
                hour = Integer.parseInt(iso.substring(11, 13));
                minute = Integer.parseInt(iso.substring(14, 16));
                second = Integer.parseInt(iso.substring(17, 19));
                int i = 19;
                if (i < iso.length() && iso.charAt(i) == '.') {
                    int start = ++i;
                    while (i < iso.length() && Character.isDigit(iso.charAt(i))) {
                        i++;
                    }
                    String fraction = (iso.substring(start, i) + "000").substring(0, 3);
                    millis = Integer.parseInt(fraction);
                }
                if (i < iso.length()) {
                    char sign = iso.charAt(i);
                    if (sign == '+' || sign == '-') {
                        String rest = iso.substring(i + 1).replace(":", "");
                        int offHours = Integer.parseInt(rest.substring(0, 2));
                        int offMinutes = rest.length() >= 4 ? Integer.parseInt(rest.substring(2, 4)) : 0;
                        offsetMillis = (offHours * 60L + offMinutes) * 60_000L * (sign == '-' ? -1 : 1);
                    }
                }
            }
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            calendar.clear();
            calendar.set(year, month - 1, day, hour, minute, second);
            calendar.set(Calendar.MILLISECOND, millis);
            return calendar.getTimeInMillis() - offsetMillis;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * A {@code yyyy-MM-dd} day as a local calendar at midnight, or null when unreadable. Local,
     * so "2026-10-03" stays the 3rd whatever the device's zone.
     */
    public static Calendar parseDay(String day) {
        if (day == null || day.length() < 10) {
            return null;
        }
        try {
            Calendar calendar = Calendar.getInstance();
            calendar.clear();
            calendar.set(Integer.parseInt(day.substring(0, 4)),
                    Integer.parseInt(day.substring(5, 7)) - 1,
                    Integer.parseInt(day.substring(8, 10)));
            return calendar;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
