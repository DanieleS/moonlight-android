package com.limelight.nvstream.http;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// Robolectric for Android's own org.json, whose quirks (a JSON null read as "null") are the ones
// the parsing has to survive.
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class StatsModelsTest {

    private static final String OVERVIEW = "{"
            + "\"range\":\"week\",\"offset\":-1,\"from\":\"2026-09-28\",\"to\":\"2026-10-05\","
            + "\"today\":\"2026-10-08\",\"tracking_since\":\"2026-08-01\","
            + "\"total_seconds\":12345,\"previous_total_seconds\":6000,\"previous_from\":\"2026-09-21\","
            + "\"sessions\":4,"
            + "\"buckets\":[{\"date\":\"2026-09-28\",\"seconds\":3600},{\"date\":\"2026-09-29\",\"seconds\":0}],"
            + "\"top\":[{\"uuid\":\"AAA\",\"name\":\"Hades\",\"seconds\":9000}],"
            + "\"library\":{\"playtime_seconds\":3600000,\"games\":120,\"installed\":30,"
            + "\"installed_never_played\":7,\"played_this_year\":25},"
            + "\"resume\":[{\"uuid\":\"BBB\",\"name\":\"Celeste\",\"playtime_seconds\":7200,"
            + "\"last_activity\":\"2026-03-01T10:00:00Z\"}],"
            + "\"achievements\":{\"unlocked\":3,\"recent\":[{\"id\":\"ACH_1\",\"name\":\"First\","
            + "\"description\":\"d\",\"unlocked\":true,\"unlocked_at\":\"2026-09-30T20:15:00Z\","
            + "\"hidden\":false,\"percent\":4.5,\"gamer_score\":null,"
            + "\"icon\":\"https://cdn.example/1.jpg\",\"locked_icon\":null,\"uuid\":\"AAA\",\"game\":\"Hades\"}]}"
            + "}";

    @Test
    public void parsesTheOverview() throws Exception {
        AppStats s = AppStats.fromJson(new JSONObject(OVERVIEW));
        assertEquals("week", s.getRange());
        assertEquals(-1, s.getOffset());
        assertEquals("2026-09-28", s.getFrom());
        assertEquals("2026-10-05", s.getTo());
        assertEquals(12345, s.getTotalSeconds());
        assertEquals(6000, s.getPreviousTotalSeconds());
        assertEquals(4, s.getSessions());
        assertEquals(2, s.getBuckets().size());
        assertEquals(3600, s.getBuckets().get(0).getSeconds());
        assertEquals("Hades", s.getTop().get(0).getName());
        assertEquals(9000, s.getTop().get(0).getSeconds());
        assertEquals(120, s.getLibrary().getGames());
        assertEquals(7, s.getLibrary().getInstalledNeverPlayed());
        assertEquals(25, s.getLibrary().getPlayedThisYear());
        assertEquals("Celeste", s.getResume().get(0).getName());
        assertEquals("2026-03-01T10:00:00Z", s.getResume().get(0).getLastActivity());

        assertNotNull(s.getAchievements());
        assertEquals(3, s.getAchievements().getUnlocked());
        Achievement a = s.getAchievements().getRecent().get(0);
        assertEquals("ACH_1", a.getId());
        assertEquals("AAA", a.getAppUuid());
        assertEquals("Hades", a.getGame());
        assertEquals(4.5, a.getPercent(), 0.0001);
        assertNull(a.getGamerScore());
        assertNull(a.getLockedIcon());

        // A past week, logged in full, with the week before it logged too.
        assertFalse(s.isRunning());
        assertTrue(s.isTracked());
        assertTrue(s.isComparable());
    }

    @Test
    public void nullAchievementsMeansNoSuccessStory() throws Exception {
        JSONObject json = new JSONObject(OVERVIEW);
        json.put("achievements", JSONObject.NULL);
        json.put("tracking_since", JSONObject.NULL);
        AppStats s = AppStats.fromJson(json);
        assertNull(s.getAchievements());
        assertNull(s.getTrackingSince());
        assertFalse(s.isTracked());
        assertFalse(s.isComparable());
    }

    @Test
    public void aPeriodUnderWayIsRunning() throws Exception {
        JSONObject json = new JSONObject(OVERVIEW);
        json.put("from", "2026-10-05").put("to", "2026-10-12").put("previous_from", "2026-09-28");
        assertTrue(AppStats.fromJson(json).isRunning());
    }

    @Test
    public void aLogStartedMidPeriodIsTrackedButNotComparable() throws Exception {
        JSONObject json = new JSONObject(OVERVIEW);
        json.put("tracking_since", "2026-09-30");
        AppStats s = AppStats.fromJson(json);
        assertTrue(s.isTracked());
        assertFalse(s.isComparable());

        json.put("tracking_since", "2026-10-05");
        assertFalse(AppStats.fromJson(json).isTracked());
    }

    @Test
    public void parsesAGame() throws Exception {
        GameStats g = GameStats.fromJson(new JSONObject("{\"uuid\":\"AAA\",\"playtime_seconds\":36000,"
                + "\"play_count\":12,\"last_activity\":\"2026-10-07T21:00:00Z\",\"sessions\":5,"
                + "\"average_seconds\":1800,\"weeks\":[0,0,0,0,0,0,0,0,0,0,600,1200],"
                + "\"last_session\":{\"start\":\"2026-10-07T20:00:00Z\",\"seconds\":3600},"
                + "\"tracking_since\":\"2026-08-01\"}"));
        assertEquals(36000, g.getPlaytimeSeconds());
        assertEquals(12, g.getPlayCount());
        assertEquals(5, g.getSessions());
        assertEquals(1800, g.getAverageSeconds());
        assertEquals(1200, g.getWeeks()[11]);
        assertEquals(600, g.getWeeks()[10]);
        assertTrue(g.hasLastSession());
        assertEquals(3600, g.getLastSessionSeconds());
        assertEquals("2026-08-01", g.getTrackingSince());
    }

    @Test
    public void aGameWithoutSessionsHasNoLastSession() throws Exception {
        GameStats g = GameStats.fromJson(new JSONObject("{\"uuid\":\"AAA\",\"playtime_seconds\":0,"
                + "\"play_count\":0,\"last_activity\":null,\"sessions\":0,\"average_seconds\":0,"
                + "\"weeks\":[1,2,3],\"last_session\":null,\"tracking_since\":null}"));
        assertFalse(g.hasLastSession());
        assertNull(g.getLastActivity());
        assertNull(g.getTrackingSince());
        // A short list is right-aligned: its last entry is still this week.
        assertArrayEquals(new long[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3}, g.getWeeks());
    }

    @Test
    public void parsesAGamesAchievements() throws Exception {
        GameAchievements g = GameAchievements.fromJson(new JSONObject("{\"uuid\":\"AAA\",\"total\":3,"
                + "\"unlocked\":1,\"last_refresh\":\"2026-10-07T22:00:00Z\",\"items\":["
                + "{\"id\":\"A\",\"name\":\"One\",\"description\":\"\",\"unlocked\":true,"
                + "\"unlocked_at\":null,\"hidden\":false,\"percent\":50,\"gamer_score\":10,"
                + "\"icon\":\"/appachievementicon?appuuid=AAA&index=0\",\"locked_icon\":null},"
                + "{\"id\":\"1\",\"name\":\"Two\",\"description\":\"Secret\",\"unlocked\":false,"
                + "\"unlocked_at\":null,\"hidden\":true,\"percent\":null,\"gamer_score\":20,"
                + "\"icon\":\"https://cdn.example/2.jpg\",\"locked_icon\":\"https://cdn.example/2g.jpg\"},"
                + "{\"id\":\"2\",\"name\":\"Three\",\"unlocked\":false,\"hidden\":false}]}"));
        assertEquals(3, g.getTotal());
        assertEquals(1, g.getUnlocked());
        assertEquals(33, g.getPercent());
        assertArrayEquals(new long[]{10, 30}, g.getGamerScore());

        List<Achievement> items = g.getItems();
        // Unlocked, but SuccessStory doesn't know when: unlocked with no date.
        assertTrue(items.get(0).isUnlocked());
        assertNull(items.get(0).getUnlockedAt());
        assertEquals(0, items.get(0).getUnlockedAtMillis());
        assertEquals("/appachievementicon?appuuid=AAA&index=0", items.get(0).getDisplayIcon());
        assertFalse(items.get(0).needsGreyedIcon());

        // Locked with a locked icon of its own: that one, not greyed.
        assertTrue(items.get(1).isHidden());
        assertNull(items.get(1).getPercent());
        assertEquals("https://cdn.example/2g.jpg", items.get(1).getDisplayIcon());
        assertFalse(items.get(1).needsGreyedIcon());

        // Locked without one: the unlocked icon (here none), greyed.
        assertTrue(items.get(2).needsGreyedIcon());
        assertNull(items.get(2).getDisplayIcon());
        assertEquals("", items.get(2).getDescription());
    }

    @Test
    public void noGamerscoreOutsideXbox() throws Exception {
        GameAchievements g = GameAchievements.fromJson(new JSONObject("{\"uuid\":\"AAA\",\"total\":1,"
                + "\"unlocked\":0,\"items\":[{\"id\":\"A\",\"name\":\"One\",\"unlocked\":false}]}"));
        assertNull(g.getGamerScore());
        assertNull(g.getLastRefresh());
    }

    @Test
    public void readsIso8601Instants() {
        assertEquals(1_759_868_100_000L, Iso8601.parseMillis("2025-10-07T20:15:00Z"));
        assertEquals(1_759_868_100_123L, Iso8601.parseMillis("2025-10-07T20:15:00.1234567Z"));
        assertEquals(1_759_868_100_000L, Iso8601.parseMillis("2025-10-07T22:15:00+02:00"));
        assertEquals(1_759_868_100_000L, Iso8601.parseMillis("2025-10-07T15:15:00-0500"));
        assertEquals(1_759_868_100_000L, Iso8601.parseMillis("2025-10-07T20:15:00"));
        assertEquals(0, Iso8601.parseMillis(null));
        assertEquals(0, Iso8601.parseMillis("soon"));
    }

    @Test
    public void readsDaysAsLocalDates() {
        java.util.Calendar day = Iso8601.parseDay("2026-10-03");
        assertNotNull(day);
        assertEquals(2026, day.get(java.util.Calendar.YEAR));
        assertEquals(java.util.Calendar.OCTOBER, day.get(java.util.Calendar.MONTH));
        assertEquals(3, day.get(java.util.Calendar.DAY_OF_MONTH));
        assertNull(Iso8601.parseDay(""));
    }
}
