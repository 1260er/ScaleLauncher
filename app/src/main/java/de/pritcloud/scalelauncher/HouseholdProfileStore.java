package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class HouseholdProfileStore {
    private static final String PREFS =
            "household_profiles_v1";

    private static final String KEY =
            "profiles";

    private HouseholdProfileStore() {}

    static List<HouseholdProfile> load(
            Context context) {
        return load(prefs(context));
    }

    static List<HouseholdProfile> load(
            SharedPreferences preferences) {
        List<HouseholdProfile> result =
                new ArrayList<>();

        String encoded =
                preferences.getString(
                        KEY,
                        "");

        if (encoded == null
                || encoded.isBlank()) {
            return result;
        }

        try {
            JSONArray array =
                    new JSONArray(encoded);

            for (int index = 0;
                 index < array.length();
                 index++) {
                JSONObject object =
                        array.optJSONObject(index);

                HouseholdProfile profile =
                        HouseholdProfile.fromJson(
                                object);

                if (profile != null) {
                    result.add(profile);
                }
            }
        } catch (JSONException ignored) {
        }

        return result;
    }

    static HouseholdProfile find(
            Context context,
            String profileId) {
        for (HouseholdProfile profile :
                load(context)) {
            if (profile.profileId.equals(
                    profileId)) {
                return profile;
            }
        }

        return null;
    }

    static boolean upsert(
            Context context,
            HouseholdProfile incoming) {
        return upsert(
                prefs(context),
                incoming);
    }

    static boolean upsert(
            SharedPreferences preferences,
            HouseholdProfile incoming) {
        if (incoming == null
                || !incoming.isValid()) {
            return false;
        }

        List<HouseholdProfile> profiles =
                load(preferences);

        HouseholdProfile existing = null;
        int existingIndex = -1;

        for (int index = 0;
             index < profiles.size();
             index++) {
            HouseholdProfile candidate =
                    profiles.get(index);

            if (candidate.profileId.equals(
                    incoming.profileId)) {
                existing = candidate;
                existingIndex = index;
                break;
            }
        }

        if (existing != null
                && !existing.ownerDeviceId.equals(
                        incoming.ownerDeviceId)) {
            return false;
        }

        if (existing != null
                && existing.updatedAtMs
                >= incoming.updatedAtMs) {
            return false;
        }

        if (existingIndex >= 0) {
            profiles.set(
                    existingIndex,
                    incoming);
        } else {
            profiles.add(
                    incoming);
        }

        save(
                preferences,
                profiles);

        return true;
    }

    static boolean removeProfile(
            Context context,
            String profileId) {
        List<HouseholdProfile> profiles =
                load(context);

        boolean removed =
                profiles.removeIf(
                        profile ->
                                profile.profileId.equals(
                                        profileId));

        if (removed) {
            save(
                    context,
                    profiles);
        }

        return removed;
    }

    static int removeOwnerExcept(
            Context context,
            String ownerDeviceId,
            List<String> retainedProfileIds) {
        if (!PeerTrustStore.isValidDeviceId(
                        ownerDeviceId)
                || retainedProfileIds == null) {
            return 0;
        }

        java.util.HashSet<String> retained =
                new java.util.HashSet<>();

        for (String profileId :
                retainedProfileIds) {
            if (!UserProfile.isValidHouseholdProfileId(
                    profileId)) {
                return 0;
            }

            retained.add(
                    profileId);
        }

        List<HouseholdProfile> profiles =
                load(context);

        int before =
                profiles.size();

        profiles.removeIf(
                profile ->
                        ownerDeviceId.equals(
                                profile.ownerDeviceId)
                                && !retained.contains(
                                        profile.profileId));

        int removed =
                before - profiles.size();

        if (removed > 0) {
            save(
                    context,
                    profiles);
        }

        return removed;
    }

    static int removeOwner(
            Context context,
            String ownerDeviceId) {
        return removeOwner(
                prefs(context),
                ownerDeviceId);
    }

    static int removeOwner(
            SharedPreferences preferences,
            String ownerDeviceId) {
        List<HouseholdProfile> profiles =
                load(preferences);

        int before =
                profiles.size();

        profiles.removeIf(
                profile ->
                        profile.ownerDeviceId.equals(
                                ownerDeviceId));

        int removed =
                before - profiles.size();

        if (removed > 0) {
            save(
                    preferences,
                    profiles);
        }

        return removed;
    }

    static List<HouseholdProfile> active(
            Context context) {
        List<HouseholdProfile> result =
                new ArrayList<>();

        for (HouseholdProfile profile :
                load(context)) {
            if (profile.active) {
                result.add(profile);
            }
        }

        return result;
    }

    private static void save(
            Context context,
            List<HouseholdProfile> profiles) {
        save(
                prefs(context),
                profiles);
    }

    private static void save(
            SharedPreferences preferences,
            List<HouseholdProfile> profiles) {
        JSONArray array =
                new JSONArray();

        for (HouseholdProfile profile :
                profiles) {
            try {
                array.put(
                        profile.toJson());
            } catch (JSONException ignored) {
            }
        }

        preferences
                .edit()
                .putString(
                        KEY,
                        array.toString())
                .commit();
    }

    private static SharedPreferences prefs(
            Context context) {
        return context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE);
    }
}
