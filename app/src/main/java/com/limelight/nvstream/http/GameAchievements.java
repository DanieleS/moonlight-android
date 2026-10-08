package com.limelight.nvstream.http;

import org.json.JSONObject;

import java.util.List;

/**
 * Every achievement of one game, as Vibepollo reads it from SuccessStory's file and serves it from
 * {@code /appachievements?appuuid=}. The host sorts the list the way CouchPilot does: the unlocked
 * ones first, newest first, then what is left, the most common first.
 */
public class GameAchievements {
    private final String uuid;
    private final int total;
    private final int unlocked;
    private final String lastRefresh;
    private final List<Achievement> items;

    private GameAchievements(String uuid, int total, int unlocked, String lastRefresh, List<Achievement> items) {
        this.uuid = uuid;
        this.total = total;
        this.unlocked = unlocked;
        this.lastRefresh = lastRefresh;
        this.items = items;
    }

    static GameAchievements fromJson(JSONObject obj) {
        List<Achievement> items = Json.achievements(obj.optJSONArray("items"));
        return new GameAchievements(
                Json.string(obj, "uuid", ""),
                Json.intValue(obj, "total", items.size()),
                Json.intValue(obj, "unlocked", 0),
                Json.string(obj, "last_refresh", null),
                items);
    }

    public String getUuid() {
        return uuid;
    }

    public int getTotal() {
        return total;
    }

    public int getUnlocked() {
        return unlocked;
    }

    /** When SuccessStory last refreshed this game, ISO8601 UTC, or null. */
    public String getLastRefresh() {
        return lastRefresh;
    }

    public List<Achievement> getItems() {
        return items;
    }

    /** Unlocked out of total, 0 to 100, rounded. */
    public int getPercent() {
        return total <= 0 ? 0 : Math.round(unlocked * 100f / total);
    }

    /**
     * "got / all" gamerscore, or null when no achievement of the game carries one (anything but
     * Xbox).
     */
    public long[] getGamerScore() {
        boolean any = false;
        double got = 0, all = 0;
        for (Achievement a : items) {
            Double score = a.getGamerScore();
            if (score == null || score == 0) {
                continue;
            }
            any = true;
            all += score;
            if (a.isUnlocked()) {
                got += score;
            }
        }
        return any ? new long[]{Math.round(got), Math.round(all)} : null;
    }
}
