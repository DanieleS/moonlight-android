package com.limelight.stats;

import com.limelight.nvstream.http.Achievement;
import com.limelight.nvstream.http.TestAchievements;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class AchievementFreshnessTest {
    // 2026-10-08T12:00:00Z
    private static final long NOW = 1_791_460_800_000L;

    @Test
    public void announcesRecentUnlocksOnce() {
        Set<String> seen = new HashSet<>();
        List<Achievement> list = Arrays.asList(
                TestAchievements.unlocked("AAA", "a", "2026-10-08T11:00:00Z"),
                TestAchievements.unlocked("AAA", "b", "2026-10-08T01:00:00Z"));

        List<Achievement> fresh = AchievementFreshness.select(list, seen, NOW);
        assertEquals(2, fresh.size());
        assertEquals("a", fresh.get(0).getId());

        // The next poll returns the same two: nothing new to say.
        assertTrue(AchievementFreshness.select(list, seen, NOW + 30_000).isEmpty());
    }

    @Test
    public void leavesOldUndatedAndLockedOnesOut() {
        Set<String> seen = new HashSet<>();
        List<Achievement> list = Arrays.asList(
                TestAchievements.unlocked("AAA", "old", "2026-10-07T23:59:00Z"),
                TestAchievements.unlocked("AAA", "undated", null),
                TestAchievements.locked("AAA", "locked"));
        assertTrue(AchievementFreshness.select(list, seen, NOW).isEmpty());
        // Still remembered, so they are not weighed again.
        assertTrue(seen.contains("AAA/old"));
    }

    @Test
    public void theSameIdInAnotherGameIsAnotherUnlock() {
        Set<String> seen = new HashSet<>();
        AchievementFreshness.select(Arrays.asList(
                TestAchievements.unlocked("aaa", "0", "2026-10-08T11:00:00Z")), seen, NOW);
        List<Achievement> fresh = AchievementFreshness.select(Arrays.asList(
                TestAchievements.unlocked("AAA", "0", "2026-10-08T11:00:00Z"),
                TestAchievements.unlocked("BBB", "0", "2026-10-08T11:30:00Z")), seen, NOW);
        // Same game whatever the uuid's case; the other game's "0" is news.
        assertEquals(1, fresh.size());
        assertEquals("BBB", fresh.get(0).getAppUuid());
    }

    @Test
    public void aHostClockAheadStillCounts() {
        Set<String> seen = new HashSet<>();
        assertEquals(1, AchievementFreshness.select(Arrays.asList(
                TestAchievements.unlocked("AAA", "a", "2026-10-08T12:02:00Z")), seen, NOW).size());
    }
}
