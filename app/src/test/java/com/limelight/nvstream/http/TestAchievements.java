package com.limelight.nvstream.http;

import org.json.JSONException;
import org.json.JSONObject;

/** Builds {@link Achievement}s the way the host would send them, for tests outside this package. */
public final class TestAchievements {
    private TestAchievements() {
    }

    public static Achievement unlocked(String uuid, String id, String unlockedAt) {
        return make(uuid, id, true, unlockedAt);
    }

    public static Achievement locked(String uuid, String id) {
        return make(uuid, id, false, null);
    }

    private static Achievement make(String uuid, String id, boolean unlocked, String unlockedAt) {
        try {
            return Achievement.fromJson(new JSONObject()
                    .put("id", id)
                    .put("name", "Achievement " + id)
                    .put("unlocked", unlocked)
                    .put("unlocked_at", unlockedAt == null ? JSONObject.NULL : unlockedAt)
                    .put("uuid", uuid)
                    .put("game", "Game " + uuid));
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }
}
