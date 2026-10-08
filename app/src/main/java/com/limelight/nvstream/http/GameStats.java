package com.limelight.nvstream.http;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One game's play statistics, as Vibepollo serves them from {@code /appstats?appuuid=}: Playnite's
 * totals (playtime, play count, last activity), and what the session log knows about its sessions
 * since the log began.
 */
public class GameStats {
    public static final int WEEKS = 12;

    private final String uuid;
    private final long playtimeSeconds;
    private final long playCount;
    private final String lastActivity;
    private final int sessions;
    private final long averageSeconds;
    private final long[] weeks;
    private final String lastSessionStart;
    private final long lastSessionSeconds;
    private final String trackingSince;

    private GameStats(String uuid, long playtimeSeconds, long playCount, String lastActivity, int sessions,
                      long averageSeconds, long[] weeks, String lastSessionStart, long lastSessionSeconds,
                      String trackingSince) {
        this.uuid = uuid;
        this.playtimeSeconds = playtimeSeconds;
        this.playCount = playCount;
        this.lastActivity = lastActivity;
        this.sessions = sessions;
        this.averageSeconds = averageSeconds;
        this.weeks = weeks;
        this.lastSessionStart = lastSessionStart;
        this.lastSessionSeconds = lastSessionSeconds;
        this.trackingSince = trackingSince;
    }

    static GameStats fromJson(JSONObject obj) {
        // Always twelve, the oldest first and this week last, whatever the host sent.
        long[] weeks = new long[WEEKS];
        JSONArray array = obj.optJSONArray("weeks");
        if (array != null) {
            int skip = Math.max(0, array.length() - WEEKS);
            int pad = Math.max(0, WEEKS - array.length());
            for (int i = skip; i < array.length(); i++) {
                weeks[pad + i - skip] = Math.max(0, array.optLong(i, 0));
            }
        }

        JSONObject last = obj.isNull("last_session") ? null : obj.optJSONObject("last_session");
        return new GameStats(
                Json.string(obj, "uuid", ""),
                Json.longValue(obj, "playtime_seconds", 0),
                Json.longValue(obj, "play_count", 0),
                Json.string(obj, "last_activity", null),
                Json.intValue(obj, "sessions", 0),
                Json.longValue(obj, "average_seconds", 0),
                weeks,
                last == null ? null : Json.string(last, "start", null),
                last == null ? 0 : Json.longValue(last, "seconds", 0),
                Json.string(obj, "tracking_since", null));
    }

    public String getUuid() {
        return uuid;
    }

    /** Playnite's total, which counts every session it ever saw, logged or not. */
    public long getPlaytimeSeconds() {
        return playtimeSeconds;
    }

    public long getPlayCount() {
        return playCount;
    }

    /** ISO8601 UTC, or null. */
    public String getLastActivity() {
        return lastActivity;
    }

    /** Sessions in the log; 0 when the log started after the game was last played. */
    public int getSessions() {
        return sessions;
    }

    public long getAverageSeconds() {
        return averageSeconds;
    }

    /** Seconds played in each of the last twelve weeks, oldest first, this week last. */
    public long[] getWeeks() {
        return weeks.clone();
    }

    public boolean hasLastSession() {
        return lastSessionStart != null;
    }

    /** ISO8601, or null when the log has no session of this game. */
    public String getLastSessionStart() {
        return lastSessionStart;
    }

    public long getLastSessionSeconds() {
        return lastSessionSeconds;
    }

    public String getTrackingSince() {
        return trackingSince;
    }
}
