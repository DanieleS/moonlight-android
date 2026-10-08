package com.limelight.nvstream.http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Small readers for the host's JSON that treat a missing key and an explicit {@code null} the
 * same way. Android's {@code optString} turns a JSON null into the four letters "null", which is
 * the one thing a nullable field such as {@code unlocked_at} must never become.
 */
final class Json {
    private Json() {
    }

    static String string(JSONObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.isNull(key)) {
            return fallback;
        }
        Object value = obj.opt(key);
        return value == null ? fallback : value.toString();
    }

    /** A number, or null when the key is missing, null, or not a number. */
    static Double number(JSONObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.isNull(key)) {
            return null;
        }
        Object value = obj.opt(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static long longValue(JSONObject obj, String key, long fallback) {
        Double value = number(obj, key);
        return value == null ? fallback : Math.round(value);
    }

    static int intValue(JSONObject obj, String key, int fallback) {
        Double value = number(obj, key);
        return value == null ? fallback : (int) Math.round(value);
    }

    static List<JSONObject> objects(JSONArray array) {
        if (array == null || array.length() == 0) {
            return Collections.emptyList();
        }
        List<JSONObject> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    static List<Achievement> achievements(JSONArray array) {
        List<Achievement> out = new ArrayList<>();
        for (JSONObject item : objects(array)) {
            out.add(Achievement.fromJson(item));
        }
        return Collections.unmodifiableList(out);
    }
}
