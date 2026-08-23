package de.pritcloud.scalelauncher;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.UUID;

final class UserProfile {
    static final float DEFAULT_TOLERANCE_KG = 2.0f;

    final long userId;
    String name;
    boolean enabled;
    String birthDateIso;
    float heightCm;
    boolean male;
    float referenceWeightKg;
    float toleranceKg;
    String ownerDeviceId;
    String householdProfileId;
    long householdUpdatedAtMs;

    UserProfile(long userId, String name) {
        this.userId = userId;
        this.name = name == null || name.isBlank()
                ? "#" + userId
                : name;
        this.birthDateIso = "";
        this.toleranceKg = DEFAULT_TOLERANCE_KG;
        this.ownerDeviceId = "";
        this.householdProfileId = "";
    }

    boolean hasValidBodyData(long timestampMs) {
        LocalDate birthDate = BirthDateUtils.parseIso(birthDateIso);
        int age = BirthDateUtils.ageOn(birthDate, timestampMs);
        return age >= 18 && age <= 120
                && Float.isFinite(heightCm)
                && heightCm >= 100f
                && heightCm <= 230f;
    }

    boolean hasValidMatchingData() {
        return enabled
                && Float.isFinite(referenceWeightKg)
                && referenceWeightKg > 0f
                && Float.isFinite(toleranceKg)
                && toleranceKg > 0f
                && toleranceKg <= 30f;
    }

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("userId", userId);
        object.put("name", name);
        object.put("enabled", enabled);
        object.put("birthDate", birthDateIso);
        object.put("heightCm", heightCm);
        object.put("male", male);
        object.put("referenceWeightKg", referenceWeightKg);
        object.put("toleranceKg", toleranceKg);
        object.put("ownerDeviceId", ownerDeviceId == null ? "" : ownerDeviceId);
        object.put("householdProfileId",
                householdProfileId == null ? "" : householdProfileId);
        object.put("householdUpdatedAtMs", householdUpdatedAtMs);
        return object;
    }

    static UserProfile fromJson(JSONObject object) {
        long id = object.optLong("userId", -1L);
        UserProfile profile = new UserProfile(id, object.optString("name", ""));
        profile.enabled = object.optBoolean("enabled", false);
        profile.birthDateIso = object.optString("birthDate", "");
        profile.heightCm = (float) object.optDouble("heightCm", 0.0d);
        profile.male = object.optBoolean("male", false);
        profile.referenceWeightKg = (float) object.optDouble("referenceWeightKg", 0.0d);
        profile.toleranceKg = (float) object.optDouble("toleranceKg", DEFAULT_TOLERANCE_KG);
        profile.ownerDeviceId = object.optString("ownerDeviceId", "");
        profile.householdProfileId =
                object.optString("householdProfileId", "");
        profile.householdUpdatedAtMs =
                object.optLong("householdUpdatedAtMs", 0L);
        return profile;
    }

    String ensureHouseholdProfileId() {
        if (isValidHouseholdProfileId(
                householdProfileId)) {
            return householdProfileId;
        }

        householdProfileId =
                UUID.randomUUID().toString();

        return householdProfileId;
    }

    static boolean isValidHouseholdProfileId(
            String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Override public String toString() {
        return name;
    }
}
