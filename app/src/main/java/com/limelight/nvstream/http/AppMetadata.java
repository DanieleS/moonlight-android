package com.limelight.nvstream.http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Playnite-enriched metadata for a single app, as served by the host's {@code /appmetadata}
 * endpoint (a Vibepollo fork addition). Every field is optional: hosts that don't provide the
 * endpoint, or apps that carry no metadata, simply yield nothing.
 */
public class AppMetadata {
    public static final int NO_SCORE = -1;

    private final String name;
    private final String description;
    private final List<String> genres;
    private final List<String> developers;
    private final List<String> publishers;
    private final String releaseDate;
    private final int communityScore;
    private final int criticScore;
    private final String lastPlayed;
    private final long playtimeMinutes;
    private final boolean hasBackground;

    private AppMetadata(String name, String description, List<String> genres, List<String> developers,
                        List<String> publishers, String releaseDate, int communityScore, int criticScore,
                        String lastPlayed, long playtimeMinutes, boolean hasBackground) {
        this.name = name;
        this.description = description;
        this.genres = genres;
        this.developers = developers;
        this.publishers = publishers;
        this.releaseDate = releaseDate;
        this.communityScore = communityScore;
        this.criticScore = criticScore;
        this.lastPlayed = lastPlayed;
        this.playtimeMinutes = playtimeMinutes;
        this.hasBackground = hasBackground;
    }

    static AppMetadata fromJson(JSONObject obj) {
        return new AppMetadata(
                obj.optString("name", ""),
                obj.optString("description", ""),
                stringList(obj.optJSONArray("genres")),
                stringList(obj.optJSONArray("developers")),
                stringList(obj.optJSONArray("publishers")),
                obj.optString("release_date", ""),
                obj.optInt("community_score", NO_SCORE),
                obj.optInt("critic_score", NO_SCORE),
                obj.optString("last_played", ""),
                obj.optLong("playtime_minutes", 0),
                obj.optBoolean("has_background", false));
    }

    private static List<String> stringList(JSONArray array) {
        if (array == null || array.length() == 0) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, null);
            if (value != null && !value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getGenres() {
        return genres;
    }

    public List<String> getDevelopers() {
        return developers;
    }

    public List<String> getPublishers() {
        return publishers;
    }

    public String getReleaseDate() {
        return releaseDate;
    }

    /** 0-100, or {@link #NO_SCORE} when the host has no community score for this game. */
    public int getCommunityScore() {
        return communityScore;
    }

    /** 0-100, or {@link #NO_SCORE} when the host has no critic score for this game. */
    public int getCriticScore() {
        return criticScore;
    }

    /**
     * When Playnite last recorded this game as played, ISO8601, or empty when it has never been
     * played or the host does not track it. This is the host's own figure — it counts sessions
     * played at the PC itself, which a device-local launch record cannot see.
     */
    public String getLastPlayed() {
        return lastPlayed;
    }

    /**
     * {@link #getLastPlayed()} as milliseconds since the epoch, or 0 when there is nothing to
     * parse. Only the calendar-second portion is read: the offset and any fractional seconds are
     * ignored, which is enough to order a library and sidesteps the ISO8601 dialects Playnite can
     * emit (trailing {@code Z}, numeric offsets, seven-digit fractions).
     */
    public long getLastPlayedEpochMillis() {
        return parseIso8601Millis(lastPlayed);
    }

    /** Total minutes Playnite has this game played for, across all devices, or 0 when unknown. */
    public long getPlaytimeMinutes() {
        return playtimeMinutes;
    }

    private static long parseIso8601Millis(String iso) {
        if (iso == null || iso.length() < 10) {
            return 0;
        }
        String pattern = iso.length() >= 19 ? "yyyy-MM-dd'T'HH:mm:ss" : "yyyy-MM-dd";
        String head = iso.substring(0, pattern.length() == 10 ? 10 : 19);
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Date date = format.parse(head);
            return date == null ? 0 : date.getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    /** True when the host can serve a background/hero image for this game via /appbackground. */
    public boolean hasBackground() {
        return hasBackground;
    }

    /** The year portion of the release date, or empty when unknown / unparseable. */
    public String getReleaseYear() {
        if (releaseDate == null || releaseDate.length() < 4) {
            return "";
        }
        String year = releaseDate.substring(0, 4);
        for (int i = 0; i < year.length(); i++) {
            if (!Character.isDigit(year.charAt(i))) {
                return "";
            }
        }
        return year;
    }
}
