package com.limelight.stats;

import com.limelight.nvstream.http.Achievement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Which unlocks are news. CouchPilot's rule: one is announced once, and only while it is fresh —
 * unlocked within the last twelve hours. SuccessStory often refreshes only when a game closes, so
 * an unlock can reach us hours after it happened; past twelve hours it is history, not news.
 */
public final class AchievementFreshness {
    public static final long FRESH_MS = 12L * 3600 * 1000;

    private AchievementFreshness() {
    }

    /**
     * The unlocks in {@code list} not in {@code seen} and dated within {@link #FRESH_MS} of
     * {@code nowMillis}, in the list's order (the host's, newest first). Every key in the list is
     * added to {@code seen}, fresh or not, so a stale one is not reconsidered either.
     */
    public static List<Achievement> select(Collection<Achievement> list, Set<String> seen, long nowMillis) {
        List<Achievement> fresh = new ArrayList<>();
        if (list == null) {
            return fresh;
        }
        for (Achievement a : list) {
            if (!a.isUnlocked()) {
                continue;
            }
            String key = a.getKey();
            if (!seen.add(key)) {
                continue;
            }
            long when = a.getUnlockedAtMillis();
            if (when > 0 && nowMillis - when < FRESH_MS) {
                fresh.add(a);
            }
        }
        return fresh;
    }
}
