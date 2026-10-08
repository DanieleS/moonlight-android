package com.limelight.stats;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StatsFormatTest {
    private static final StatsFormat.Words EN = new StatsFormat.Words() {
        @Override
        public String minutes(long minutes) {
            return minutes + " min";
        }

        @Override
        public String hours(long hours, long minutes) {
            return minutes == 0 ? hours + "h" : String.format(Locale.US, "%dh %02dm", hours, minutes);
        }
    };

    @Test
    public void durationsRoundToTheMinute() {
        assertEquals("0 min", StatsFormat.duration(0, EN));
        assertEquals("1 min", StatsFormat.duration(89, EN));
        assertEquals("59 min", StatsFormat.duration(3569, EN));
        assertEquals("1h", StatsFormat.duration(3570, EN));
        assertEquals("3h 05m", StatsFormat.duration(3 * 3600 + 5 * 60, EN));
        assertEquals("0 min", StatsFormat.duration(-5, EN));
    }

    @Test
    public void barLabelsSayLittle() {
        assertEquals("", StatsFormat.shortDuration(20));
        assertEquals("45′", StatsFormat.shortDuration(45 * 60));
        assertEquals("3:20", StatsFormat.shortDuration(3 * 3600 + 20 * 60));
        assertEquals("1:05", StatsFormat.shortDuration(3600 + 5 * 60));
    }

    @Test
    public void rarityBandsFollowSuccessStory() {
        assertNull(StatsFormat.rarity(null));
        assertEquals(StatsFormat.Rarity.ULTRA_RARE, StatsFormat.rarity(0.4));
        assertEquals(StatsFormat.Rarity.ULTRA_RARE, StatsFormat.rarity(1.99));
        assertEquals(StatsFormat.Rarity.RARE, StatsFormat.rarity(2.0));
        assertEquals(StatsFormat.Rarity.RARE, StatsFormat.rarity(9.9));
        assertEquals(StatsFormat.Rarity.UNCOMMON, StatsFormat.rarity(10.0));
        assertEquals(StatsFormat.Rarity.UNCOMMON, StatsFormat.rarity(29.9));
        assertNull(StatsFormat.rarity(30.0));
        assertNull(StatsFormat.rarity(87.0));
    }

    @Test
    public void percentagesKeepADecimalOnlyWhenSmall() {
        assertEquals("0.4", StatsFormat.percent(0.4, Locale.US));
        assertEquals("0,4", StatsFormat.percent(0.4, Locale.ITALY));
        assertEquals("7", StatsFormat.percent(7.0, Locale.US));
        assertEquals("42", StatsFormat.percent(42.4, Locale.US));
        assertEquals("43", StatsFormat.percent(42.6, Locale.US));
    }

    @Test
    public void daysAgoCountsCalendarDays() {
        long now = new java.util.GregorianCalendar(2026, 9, 8, 0, 30).getTimeInMillis();
        long lateYesterday = new java.util.GregorianCalendar(2026, 9, 7, 23, 50).getTimeInMillis();
        long earlyToday = new java.util.GregorianCalendar(2026, 9, 8, 0, 5).getTimeInMillis();
        long lastWeek = new java.util.GregorianCalendar(2026, 9, 1, 12, 0).getTimeInMillis();
        assertEquals(0, StatsFormat.daysAgo(earlyToday, now));
        assertEquals(1, StatsFormat.daysAgo(lateYesterday, now));
        assertEquals(7, StatsFormat.daysAgo(lastWeek, now));
    }

    @Test
    public void aMinuteApartIsTheSame() {
        assertTrue(StatsFormat.isSame(3600, 3559));
        assertFalse(StatsFormat.isSame(3600, 3540));
    }

    @Test
    public void rangesCycleBothWays() {
        assertEquals("month", StatsFormat.cycleRange("week", 1));
        assertEquals("year", StatsFormat.cycleRange("month", 1));
        assertEquals("week", StatsFormat.cycleRange("year", 1));
        assertEquals("year", StatsFormat.cycleRange("week", -1));
        assertEquals("month", StatsFormat.cycleRange("nonsense", 1));
    }

    @Test
    public void offsetsStayBetweenTheLimits() {
        assertEquals(-1, StatsFormat.stepOffset(0, -1, -500));
        assertEquals(0, StatsFormat.stepOffset(0, 1, -500));
        assertEquals(-500, StatsFormat.stepOffset(-500, -1, -500));
        assertEquals(-4, StatsFormat.stepOffset(-5, 1, -500));
    }

    @Test
    public void sharesNeverDivideByZero() {
        assertEquals(0f, StatsFormat.share(10, 0), 0f);
        assertEquals(0.5f, StatsFormat.share(5, 10), 0.0001f);
        assertEquals(1f, StatsFormat.share(15, 10), 0f);
    }
}
