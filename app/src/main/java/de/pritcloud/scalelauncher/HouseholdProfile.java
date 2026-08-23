package de.pritcloud.scalelauncher;

import org.json.JSONException;
import org.json.JSONObject;

final class HouseholdProfile {
    final String profileId;
    final String name;
    final String ownerDeviceId;
    final float referenceWeightKg;
    final float toleranceKg;
    final boolean active;
    final long updatedAtMs;

    HouseholdProfile(
            String profileId,
            String name,
            String ownerDeviceId,
            float referenceWeightKg,
            float toleranceKg,
            boolean active,
            long updatedAtMs) {
        this.profileId = profileId;
        this.name =
                name == null
                        ? ""
                        : name.trim();
        this.ownerDeviceId = ownerDeviceId;
        this.referenceWeightKg = referenceWeightKg;
        this.toleranceKg = toleranceKg;
        this.active = active;
        this.updatedAtMs = updatedAtMs;
    }

    static HouseholdProfile fromUserProfile(
            UserProfile profile,
            String localDeviceId,
            long updatedAtMs) {
        if (profile == null) {
            throw new IllegalArgumentException(
                    "Missing user profile");
        }

        String owner =
                PeerTrustStore.isValidDeviceId(
                        profile.ownerDeviceId)
                        ? profile.ownerDeviceId
                        : localDeviceId;

        HouseholdProfile result =
                new HouseholdProfile(
                        profile.ensureHouseholdProfileId(),
                        profile.name,
                        owner,
                        profile.referenceWeightKg,
                        profile.toleranceKg,
                        profile.enabled,
                        updatedAtMs);

        if (!result.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid household profile");
        }

        return result;
    }

    boolean isValid() {
        return UserProfile.isValidHouseholdProfileId(
                        profileId)
                && name != null
                && !name.isBlank()
                && name.length() <= 100
                && PeerTrustStore.isValidDeviceId(
                        ownerDeviceId)
                && Float.isFinite(
                        referenceWeightKg)
                && referenceWeightKg > 0f
                && referenceWeightKg <= 500f
                && Float.isFinite(
                        toleranceKg)
                && toleranceKg >= 0.2f
                && toleranceKg <= 30f
                && updatedAtMs > 0L;
    }

    JSONObject toJson()
            throws JSONException {
        JSONObject object =
                new JSONObject();

        object.put(
                "profileId",
                profileId);
        object.put(
                "name",
                name);
        object.put(
                "ownerDeviceId",
                ownerDeviceId);
        object.put(
                "referenceWeightKg",
                referenceWeightKg);
        object.put(
                "toleranceKg",
                toleranceKg);
        object.put(
                "active",
                active);
        object.put(
                "updatedAtMs",
                updatedAtMs);

        return object;
    }

    static HouseholdProfile fromJson(
            JSONObject object) {
        if (object == null) {
            return null;
        }

        HouseholdProfile profile =
                new HouseholdProfile(
                        object.optString(
                                "profileId",
                                ""),
                        object.optString(
                                "name",
                                ""),
                        object.optString(
                                "ownerDeviceId",
                                ""),
                        (float) object.optDouble(
                                "referenceWeightKg",
                                Double.NaN),
                        (float) object.optDouble(
                                "toleranceKg",
                                Double.NaN),
                        object.optBoolean(
                                "active",
                                true),
                        object.optLong(
                                "updatedAtMs",
                                0L));

        return profile.isValid()
                ? profile
                : null;
    }
}
