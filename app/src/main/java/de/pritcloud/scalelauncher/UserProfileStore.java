package de.pritcloud.scalelauncher;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class UserProfileStore {
    private static final String KEY = "user_profiles_json";

    private UserProfileStore() {}

    static List<UserProfile> load(SharedPreferences prefs) {
        List<UserProfile> profiles = new ArrayList<>();
        String json = prefs.getString(KEY, "");
        if (json == null || json.isBlank()) return profiles;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                UserProfile profile = UserProfile.fromJson(item);
                if (profile.userId >= 0L) profiles.add(profile);
            }
        } catch (JSONException ignored) {
            // A damaged profile list is treated as empty and can be recreated in the UI.
        }
        return profiles;
    }

    static List<UserProfile> synchronize(SharedPreferences prefs,
                                         List<OpenScaleProvider.User> users) {
        Map<Long, UserProfile> byId = new LinkedHashMap<>();
        for (UserProfile profile : load(prefs)) byId.put(profile.userId, profile);

        boolean migrateLegacy = byId.isEmpty();
        long oldUserId = prefs.getLong("openscale_user_id", -1L);
        for (OpenScaleProvider.User user : users) {
            UserProfile profile = byId.get(user.id);
            if (profile == null) {
                profile = new UserProfile(user.id, user.name);
                if (migrateLegacy && user.id == oldUserId) {
                    profile.enabled = true;
                    profile.birthDateIso = prefs.getString("birth_date", "");
                    profile.heightCm = prefs.getFloat("height_cm", 0f);
                    profile.male = prefs.getInt("sex", 0) == 1;
                }
                byId.put(user.id, profile);
            }
            profile.name = user.name;
        }

        List<UserProfile> synchronizedProfiles = new ArrayList<>();
        for (OpenScaleProvider.User user : users) {
            UserProfile profile = byId.get(user.id);
            if (profile != null) synchronizedProfiles.add(profile);
        }
        save(prefs, synchronizedProfiles);

        java.util.HashSet<Long> currentUserIds = new java.util.HashSet<>();
        for (OpenScaleProvider.User user : users) {
            currentUserIds.add(user.id);
        }

        SharedPreferences.Editor cleanup = prefs.edit();
        boolean cleanupNeeded = false;

        long healthUserId = prefs.getLong("health_connect_user_id", -1L);
        if (healthUserId >= 0L && !currentUserIds.contains(healthUserId)) {
            cleanup.remove("health_connect_user_id");
            cleanupNeeded = true;
        }

        long editorUserId = prefs.getLong("profile_editor_user_id", -1L);
        if (editorUserId >= 0L && !currentUserIds.contains(editorUserId)) {
            cleanup.remove("profile_editor_user_id");
            cleanupNeeded = true;
        }

        long legacyUserId = prefs.getLong("openscale_user_id", -1L);
        if (legacyUserId >= 0L && !currentUserIds.contains(legacyUserId)) {
            cleanup.remove("openscale_user_id");
            cleanupNeeded = true;
        }

        if (cleanupNeeded) cleanup.apply();

        if (migrateLegacy
                && oldUserId >= 0L
                && prefs.getBoolean("health_connect_enabled", false)
                && prefs.getLong("health_connect_user_id", -1L) < 0L) {
            prefs.edit().putLong("health_connect_user_id", oldUserId).apply();
        }
        return synchronizedProfiles;
    }

    static void save(SharedPreferences prefs, List<UserProfile> profiles) {
        JSONArray array = new JSONArray();
        for (UserProfile profile : profiles) {
            try {
                array.put(profile.toJson());
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(KEY, array.toString()).apply();
    }

    static UserProfile find(List<UserProfile> profiles, long userId) {
        for (UserProfile profile : profiles) {
            if (profile.userId == userId) return profile;
        }
        return null;
    }

    static List<UserProfile> enabled(List<UserProfile> profiles) {
        List<UserProfile> enabled = new ArrayList<>();
        for (UserProfile profile : profiles) {
            if (profile.enabled) enabled.add(profile);
        }
        return enabled;
    }

    static void updateReferenceWeight(SharedPreferences prefs,
                                      long userId,
                                      float referenceWeightKg) {
        if (!Float.isFinite(referenceWeightKg) || referenceWeightKg <= 0f) return;
        List<UserProfile> profiles = load(prefs);
        UserProfile profile = find(profiles, userId);
        if (profile == null) return;
        profile.referenceWeightKg = referenceWeightKg;
        save(prefs, profiles);
    }
}
