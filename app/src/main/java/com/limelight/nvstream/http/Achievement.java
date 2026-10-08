package com.limelight.nvstream.http;

import org.json.JSONObject;

/**
 * One achievement, as SuccessStory keeps it on the host and Vibepollo serves it from
 * {@code /appachievements}, {@code /appachievements/recent} and the {@code achievements} block of
 * {@code /appstats}. Ratatoskr never fetches achievements from Steam, Xbox or anyone else: this is
 * only ever what SuccessStory already wrote down on the PC.
 *
 * <p>The game's own fields ({@link #getAppUuid()}, {@link #getGame()}) are present only in the
 * cross-game lists, where each item has to say which game it belongs to; in a single game's list
 * they are empty.
 */
public class Achievement {
    private final String id;
    private final String name;
    private final String description;
    private final boolean unlocked;
    private final String unlockedAt;
    private final boolean hidden;
    private final Double percent;
    private final Double gamerScore;
    private final String icon;
    private final String lockedIcon;
    private final String appUuid;
    private final String game;

    private Achievement(String id, String name, String description, boolean unlocked, String unlockedAt,
                        boolean hidden, Double percent, Double gamerScore, String icon, String lockedIcon,
                        String appUuid, String game) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.unlocked = unlocked;
        this.unlockedAt = unlockedAt;
        this.hidden = hidden;
        this.percent = percent;
        this.gamerScore = gamerScore;
        this.icon = icon;
        this.lockedIcon = lockedIcon;
        this.appUuid = appUuid;
        this.game = game;
    }

    static Achievement fromJson(JSONObject obj) {
        return new Achievement(
                Json.string(obj, "id", ""),
                Json.string(obj, "name", ""),
                Json.string(obj, "description", ""),
                obj.optBoolean("unlocked", false),
                Json.string(obj, "unlocked_at", null),
                obj.optBoolean("hidden", false),
                Json.number(obj, "percent"),
                Json.number(obj, "gamer_score"),
                Json.string(obj, "icon", null),
                Json.string(obj, "locked_icon", null),
                Json.string(obj, "uuid", ""),
                Json.string(obj, "game", ""));
    }

    /** SuccessStory's API name, or the achievement's position in its list when it has none. */
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isUnlocked() {
        return unlocked;
    }

    /**
     * When it was unlocked, ISO8601 UTC, or null when it is locked or SuccessStory does not know
     * the date (it writes 1982 for "unlocked, no idea when", which the host turns into null).
     */
    public String getUnlockedAt() {
        return unlockedAt;
    }

    /** {@link #getUnlockedAt()} in milliseconds since the epoch, or 0 when there is no date. */
    public long getUnlockedAtMillis() {
        return Iso8601.parseMillis(unlockedAt);
    }

    /** The game asked for this one to be kept secret until it is unlocked. */
    public boolean isHidden() {
        return hidden;
    }

    /** The share of players who have it, or null when nobody knows (no rarity, or a 100% that means none). */
    public Double getPercent() {
        return percent;
    }

    /** Xbox gamerscore, or null for stores that don't have one. */
    public Double getGamerScore() {
        return gamerScore;
    }

    /**
     * The unlocked icon: an absolute web URL, a host-relative {@code /appachievementicon?...} path
     * for icons SuccessStory keeps on disk, or null.
     */
    public String getIcon() {
        return icon;
    }

    /** The game's own locked icon, in the same forms as {@link #getIcon()}, or null when it has none. */
    public String getLockedIcon() {
        return lockedIcon;
    }

    /** The app this belongs to, in the cross-game lists; empty otherwise. */
    public String getAppUuid() {
        return appUuid;
    }

    /** The game's name, in the cross-game lists; empty otherwise. */
    public String getGame() {
        return game;
    }

    /**
     * The icon to draw: the unlocked one once it is earned; before that the game's locked one,
     * or the unlocked one greyed out by the caller (see {@link #needsGreyedIcon()}).
     */
    public String getDisplayIcon() {
        if (unlocked) {
            return icon;
        }
        return lockedIcon != null ? lockedIcon : icon;
    }

    /** Greyed out only when standing in for a locked icon the game doesn't have. */
    public boolean needsGreyedIcon() {
        return !unlocked && lockedIcon == null;
    }

    /** Identifies this unlock across every game of a host, for remembering what was announced. */
    public String getKey() {
        return appUuid.toUpperCase() + "/" + id;
    }
}
