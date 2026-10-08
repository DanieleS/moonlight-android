package com.limelight.nvstream.http;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * How much has been played on the host over a week, a month or a year, as Vibepollo serves it from
 * {@code /appstats?range=&offset=}: the period's total and buckets from the sessions the
 * GameActivity Playnite extension records, the library's totals from Playnite, and what
 * SuccessStory unlocked.
 *
 * <p>The days ({@link #getFrom()}, {@link #getTo()}, {@link #getToday()} and the bucket dates) are
 * {@code yyyy-MM-dd} days of play in the PC's calendar, which run from 5:00 to 5:00; {@code to} is
 * exclusive. Games are named only when they are in this client's catalogue, while the totals
 * count everything.
 */
public class AppStats {
    public static final String RANGE_WEEK = "week";
    public static final String RANGE_MONTH = "month";
    public static final String RANGE_YEAR = "year";

    /** The furthest back the host lets a period go. */
    public static final int MIN_OFFSET = -500;

    public static class Bucket {
        private final String date;
        private final long seconds;

        Bucket(String date, long seconds) {
            this.date = date;
            this.seconds = seconds;
        }

        /** The day, or for a year the first of the month. */
        public String getDate() {
            return date;
        }

        public long getSeconds() {
            return seconds;
        }
    }

    public static class TopGame {
        private final String uuid;
        private final String name;
        private final long seconds;

        TopGame(String uuid, String name, long seconds) {
            this.uuid = uuid;
            this.name = name;
            this.seconds = seconds;
        }

        public String getUuid() {
            return uuid;
        }

        public String getName() {
            return name;
        }

        public long getSeconds() {
            return seconds;
        }
    }

    public static class Library {
        private final long playtimeSeconds;
        private final int games;
        private final int installed;
        private final int installedNeverPlayed;
        private final int playedThisYear;

        Library(long playtimeSeconds, int games, int installed, int installedNeverPlayed, int playedThisYear) {
            this.playtimeSeconds = playtimeSeconds;
            this.games = games;
            this.installed = installed;
            this.installedNeverPlayed = installedNeverPlayed;
            this.playedThisYear = playedThisYear;
        }

        public long getPlaytimeSeconds() {
            return playtimeSeconds;
        }

        public int getGames() {
            return games;
        }

        public int getInstalled() {
            return installed;
        }

        public int getInstalledNeverPlayed() {
            return installedNeverPlayed;
        }

        public int getPlayedThisYear() {
            return playedThisYear;
        }
    }

    public static class ResumeGame {
        private final String uuid;
        private final String name;
        private final long playtimeSeconds;
        private final String lastActivity;

        ResumeGame(String uuid, String name, long playtimeSeconds, String lastActivity) {
            this.uuid = uuid;
            this.name = name;
            this.playtimeSeconds = playtimeSeconds;
            this.lastActivity = lastActivity;
        }

        public String getUuid() {
            return uuid;
        }

        public String getName() {
            return name;
        }

        public long getPlaytimeSeconds() {
            return playtimeSeconds;
        }

        /** ISO8601 UTC, or null. */
        public String getLastActivity() {
            return lastActivity;
        }
    }

    public static class Achievements {
        private final int unlocked;
        private final List<Achievement> recent;

        Achievements(int unlocked, List<Achievement> recent) {
            this.unlocked = unlocked;
            this.recent = recent;
        }

        /** How many were unlocked in the period. */
        public int getUnlocked() {
            return unlocked;
        }

        /** The latest of them, newest first, at most eight. */
        public List<Achievement> getRecent() {
            return recent;
        }
    }

    private final boolean activity;
    private final String range;
    private final int offset;
    private final String from;
    private final String to;
    private final String today;
    private final String trackingSince;
    private final long totalSeconds;
    private final long previousTotalSeconds;
    private final String previousFrom;
    private final int sessions;
    private final List<Bucket> buckets;
    private final List<TopGame> top;
    private final Library library;
    private final List<ResumeGame> resume;
    private final Achievements achievements;

    private AppStats(boolean activity, String range, int offset, String from, String to, String today, String trackingSince,
                     long totalSeconds, long previousTotalSeconds, String previousFrom, int sessions,
                     List<Bucket> buckets, List<TopGame> top, Library library, List<ResumeGame> resume,
                     Achievements achievements) {
        this.activity = activity;
        this.range = range;
        this.offset = offset;
        this.from = from;
        this.to = to;
        this.today = today;
        this.trackingSince = trackingSince;
        this.totalSeconds = totalSeconds;
        this.previousTotalSeconds = previousTotalSeconds;
        this.previousFrom = previousFrom;
        this.sessions = sessions;
        this.buckets = buckets;
        this.top = top;
        this.library = library;
        this.resume = resume;
        this.achievements = achievements;
    }

    static AppStats fromJson(JSONObject obj) {
        List<Bucket> buckets = new ArrayList<>();
        for (JSONObject b : Json.objects(obj.optJSONArray("buckets"))) {
            buckets.add(new Bucket(Json.string(b, "date", ""), Json.longValue(b, "seconds", 0)));
        }

        List<TopGame> top = new ArrayList<>();
        for (JSONObject g : Json.objects(obj.optJSONArray("top"))) {
            top.add(new TopGame(Json.string(g, "uuid", ""), Json.string(g, "name", ""),
                    Json.longValue(g, "seconds", 0)));
        }

        JSONObject lib = obj.optJSONObject("library");
        Library library = new Library(
                Json.longValue(lib, "playtime_seconds", 0),
                Json.intValue(lib, "games", 0),
                Json.intValue(lib, "installed", 0),
                Json.intValue(lib, "installed_never_played", 0),
                Json.intValue(lib, "played_this_year", 0));

        List<ResumeGame> resume = new ArrayList<>();
        for (JSONObject g : Json.objects(obj.optJSONArray("resume"))) {
            resume.add(new ResumeGame(Json.string(g, "uuid", ""), Json.string(g, "name", ""),
                    Json.longValue(g, "playtime_seconds", 0), Json.string(g, "last_activity", null)));
        }

        // Null when the host has no SuccessStory data at all, which is not the same as nothing
        // unlocked this week: the section is left out rather than shown empty.
        Achievements achievements = null;
        JSONObject ach = obj.isNull("achievements") ? null : obj.optJSONObject("achievements");
        if (ach != null) {
            achievements = new Achievements(Json.intValue(ach, "unlocked", 0),
                    Json.achievements(ach.optJSONArray("recent")));
        }

        return new AppStats(
                // Missing on hosts from before GameActivity, whose own session log is there.
                Json.bool(obj, "activity", true),
                Json.string(obj, "range", RANGE_WEEK),
                Json.intValue(obj, "offset", 0),
                Json.string(obj, "from", ""),
                Json.string(obj, "to", ""),
                Json.string(obj, "today", ""),
                Json.string(obj, "tracking_since", null),
                Json.longValue(obj, "total_seconds", 0),
                Json.longValue(obj, "previous_total_seconds", 0),
                Json.string(obj, "previous_from", ""),
                Json.intValue(obj, "sessions", 0),
                Collections.unmodifiableList(buckets),
                Collections.unmodifiableList(top),
                library,
                Collections.unmodifiableList(resume),
                achievements);
    }

    /**
     * Whether the host found GameActivity's data. Without it there are no sessions at all: the
     * period's figures are zeros and {@link #getTop()} is empty, while the library, the games to
     * resume and the achievements, which come from Playnite and SuccessStory, are all there.
     */
    public boolean hasActivity() {
        return activity;
    }

    public String getRange() {
        return range;
    }

    public int getOffset() {
        return offset;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    public String getToday() {
        return today;
    }

    /** The day of the first session GameActivity has, or null when it has none. */
    public String getTrackingSince() {
        return trackingSince;
    }

    public long getTotalSeconds() {
        return totalSeconds;
    }

    /**
     * The period before, cut to the same point while this one is still under way (the first five
     * days of last month, on the 5th of this one).
     */
    public long getPreviousTotalSeconds() {
        return previousTotalSeconds;
    }

    public String getPreviousFrom() {
        return previousFrom;
    }

    public int getSessions() {
        return sessions;
    }

    public List<Bucket> getBuckets() {
        return buckets;
    }

    public List<TopGame> getTop() {
        return top;
    }

    public Library getLibrary() {
        return library;
    }

    public List<ResumeGame> getResume() {
        return resume;
    }

    /** Null when the host has no SuccessStory data. */
    public Achievements getAchievements() {
        return achievements;
    }

    /** Whether today falls inside this period, i.e. it is still under way. */
    public boolean isRunning() {
        return !today.isEmpty() && today.compareTo(from) >= 0 && today.compareTo(to) < 0;
    }

    /**
     * Whether GameActivity's sessions reach into this period at all. Before the first one there is
     * nothing to show by day, and saying so beats a chart of zeros.
     */
    public boolean isTracked() {
        return activity && trackingSince != null && trackingSince.compareTo(to) < 0;
    }

    /**
     * Whether the comparison with the period before means anything: only when that period was
     * recorded in full, or the difference would be noise.
     */
    public boolean isComparable() {
        return activity && trackingSince != null && trackingSince.compareTo(previousFrom) <= 0;
    }
}
