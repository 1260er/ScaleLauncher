package de.pritcloud.scalelauncher;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class PendingMeasurementStore {
    private static final String KEY = "pending_measurements_json";
    private static final String CLAIMS_KEY =
            "pending_measurement_claims_json";
    private static final int MAX_ITEMS = 10;
    private static final int MAX_CLAIM_RESPONSES = 100;

    static final class ClaimResponse {
        final String measurementId;
        final String peerDeviceId;
        final List<String> profileIds;
        final long updatedAtMs;

        ClaimResponse(
                String measurementId,
                String peerDeviceId,
                List<String> profileIds,
                long updatedAtMs) {
            this.measurementId =
                    measurementId == null
                            ? ""
                            : measurementId;
            this.peerDeviceId =
                    peerDeviceId == null
                            ? ""
                            : peerDeviceId;
            this.profileIds =
                    sanitizeCandidateProfileIds(
                            profileIds);
            this.updatedAtMs =
                    updatedAtMs;
        }

        boolean isValid() {
            return !measurementId.isBlank()
                    && measurementId.length() <= 200
                    && PeerTrustStore.isValidDeviceId(
                            peerDeviceId)
                    && updatedAtMs > 0L;
        }

        JSONObject toJson()
                throws JSONException {
            JSONObject object =
                    new JSONObject();

            object.put(
                    "measurementId",
                    measurementId);
            object.put(
                    "peerDeviceId",
                    peerDeviceId);
            object.put(
                    "updatedAtMs",
                    updatedAtMs);

            JSONArray profileIdsJson =
                    new JSONArray();

            for (String profileId :
                    profileIds) {
                profileIdsJson.put(
                        profileId);
            }

            object.put(
                    "profileIds",
                    profileIdsJson);

            return object;
        }

        static ClaimResponse fromJson(
                JSONObject object) {
            if (object == null) {
                return null;
            }

            List<String> profileIds =
                    new ArrayList<>();

            JSONArray profileIdsJson =
                    object.optJSONArray(
                            "profileIds");

            if (profileIdsJson != null) {
                for (int index = 0;
                     index < profileIdsJson.length();
                     index++) {
                    String profileId =
                            profileIdsJson.optString(
                                    index,
                                    "");

                    if (!profileId.isBlank()) {
                        profileIds.add(
                                profileId);
                    }
                }
            }

            ClaimResponse response =
                    new ClaimResponse(
                            object.optString(
                                    "measurementId",
                                    ""),
                            object.optString(
                                    "peerDeviceId",
                                    ""),
                            profileIds,
                            object.optLong(
                                    "updatedAtMs",
                                    0L));

            return response.isValid()
                    ? response
                    : null;
        }
    }

    static final class Item {
        final String id;
        final float weightKg;
        final float impedanceHigh;
        final Float impedanceLow;
        final Integer scaleProfileId;
        final boolean timedOut;
        final long timestampMs;
        final String reason;
        final List<String> candidateProfileIds;

        Item(String id,
             float weightKg,
             float impedanceHigh,
             Float impedanceLow,
             Integer scaleProfileId,
             boolean timedOut,
             long timestampMs,
             String reason,
             List<String> candidateProfileIds) {
            this.id = id;
            this.weightKg = weightKg;
            this.impedanceHigh = impedanceHigh;
            this.impedanceLow = impedanceLow;
            this.scaleProfileId = scaleProfileId;
            this.timedOut = timedOut;
            this.timestampMs = timestampMs;
            this.reason = reason;
            this.candidateProfileIds =
                    sanitizeCandidateProfileIds(
                            candidateProfileIds);
        }

        S400FinalMeasurement toMeasurement() {
            return new S400FinalMeasurement(
                    id,
                    weightKg,
                    impedanceHigh,
                    impedanceLow,
                    timestampMs,
                    scaleProfileId);
        }

        JSONObject toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("id", id);
            object.put("weightKg", weightKg);
            object.put("impedanceHigh", impedanceHigh);
            if (impedanceLow != null) object.put("impedanceLow", impedanceLow);
            if (scaleProfileId != null) {
                object.put("scaleProfileId", scaleProfileId);
            }
            object.put("timedOut", timedOut);
            object.put("timestampMs", timestampMs);
            object.put("reason", reason);

            if (!candidateProfileIds.isEmpty()) {
                JSONArray candidateIds =
                        new JSONArray();

                for (String profileId :
                        candidateProfileIds) {
                    candidateIds.put(
                            profileId);
                }

                object.put(
                        "candidateProfileIds",
                        candidateIds);
            }

            return object;
        }

        static Item fromJson(JSONObject object) {
            Float low = object.has("impedanceLow")
                    ? (float) object.optDouble("impedanceLow", 0.0d)
                    : null;
            Integer scaleProfileId =
                    object.has("scaleProfileId")
                            && !object.isNull("scaleProfileId")
                            ? object.optInt("scaleProfileId")
                            : null;
            List<String> candidateProfileIds =
                    new ArrayList<>();

            JSONArray candidateIds =
                    object.optJSONArray(
                            "candidateProfileIds");

            if (candidateIds != null) {
                for (int index = 0;
                     index < candidateIds.length();
                     index++) {
                    String profileId =
                            candidateIds.optString(
                                    index,
                                    "");

                    if (!profileId.isBlank()) {
                        candidateProfileIds.add(
                                profileId);
                    }
                }
            }

            return new Item(
                    object.optString("id", ""),
                    (float) object.optDouble("weightKg", 0.0d),
                    (float) object.optDouble("impedanceHigh", 0.0d),
                    low,
                    scaleProfileId,
                    object.optBoolean("timedOut", false),
                    object.optLong("timestampMs", System.currentTimeMillis()),
                    object.optString("reason", ""),
                    candidateProfileIds);
        }
    }

    private PendingMeasurementStore() {}

    private static List<String> sanitizeCandidateProfileIds(
            List<String> candidateProfileIds) {
        Set<String> result =
                new LinkedHashSet<>();

        if (candidateProfileIds != null) {
            for (String profileId :
                    candidateProfileIds) {
                if (UserProfile.isValidHouseholdProfileId(
                        profileId)) {
                    result.add(
                            profileId);
                }
            }
        }

        return new ArrayList<>(
                result);
    }

    static List<Item> load(SharedPreferences prefs) {
        List<Item> items = new ArrayList<>();
        String json = prefs.getString(KEY, "");
        if (json == null || json.isBlank()) return items;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                Item item = Item.fromJson(object);
                if (!item.id.isBlank() && item.weightKg > 0f) items.add(item);
            }
        } catch (JSONException ignored) {
        }
        return items;
    }

    static Item add(SharedPreferences prefs,
                    S400FinalMeasurement measurement,
                    String reason) {
        return add(
                prefs,
                measurement,
                reason,
                List.of());
    }

    static Item add(SharedPreferences prefs,
                    S400FinalMeasurement measurement,
                    String reason,
                    List<String> candidateProfileIds) {
        List<Item> items = load(prefs);
        Item item = new Item(
                measurement.measurementId,
                measurement.weightKg,
                measurement.impedanceHigh,
                measurement.impedanceLow,
                measurement.scaleProfileId,
                false,
                measurement.timestampMs,
                reason,
                candidateProfileIds);
        items.add(item);
        while (items.size() > MAX_ITEMS) items.remove(0);
        save(prefs, items);
        return item;
    }

    static void recordClaimResponse(
            SharedPreferences prefs,
            String measurementId,
            String peerDeviceId,
            List<String> profileIds) {
        ClaimResponse incoming =
                new ClaimResponse(
                        measurementId,
                        peerDeviceId,
                        profileIds,
                        System.currentTimeMillis());

        if (!incoming.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid pending claim response");
        }

        List<ClaimResponse> responses =
                loadClaimResponses(
                        prefs);

        responses.removeIf(
                response ->
                        response.measurementId.equals(
                                measurementId)
                                && response.peerDeviceId.equals(
                                        peerDeviceId));

        responses.add(
                incoming);

        while (responses.size()
                > MAX_CLAIM_RESPONSES) {
            responses.remove(0);
        }

        saveClaimResponses(
                prefs,
                responses);
    }

    static List<ClaimResponse> claimResponses(
            SharedPreferences prefs,
            String measurementId) {
        List<ClaimResponse> result =
                new ArrayList<>();

        if (measurementId == null
                || measurementId.isBlank()) {
            return result;
        }

        for (ClaimResponse response :
                loadClaimResponses(
                        prefs)) {
            if (measurementId.equals(
                    response.measurementId)) {
                result.add(
                        response);
            }
        }

        return result;
    }

    private static List<ClaimResponse> loadClaimResponses(
            SharedPreferences prefs) {
        List<ClaimResponse> result =
                new ArrayList<>();

        String encoded =
                prefs.getString(
                        CLAIMS_KEY,
                        "");

        if (encoded == null
                || encoded.isBlank()) {
            return result;
        }

        try {
            JSONArray array =
                    new JSONArray(
                            encoded);

            for (int index = 0;
                 index < array.length();
                 index++) {
                ClaimResponse response =
                        ClaimResponse.fromJson(
                                array.optJSONObject(
                                        index));

                if (response != null) {
                    result.add(
                            response);
                }
            }
        } catch (JSONException ignored) {
        }

        return result;
    }

    private static void saveClaimResponses(
            SharedPreferences prefs,
            List<ClaimResponse> responses) {
        JSONArray array =
                new JSONArray();

        for (ClaimResponse response :
                responses) {
            try {
                array.put(
                        response.toJson());
            } catch (JSONException ignored) {
            }
        }

        prefs.edit()
                .putString(
                        CLAIMS_KEY,
                        array.toString())
                .apply();
    }

    static Item find(SharedPreferences prefs, String id) {
        for (Item item : load(prefs)) {
            if (item.id.equals(id)) return item;
        }
        return null;
    }

    static void remove(SharedPreferences prefs, String id) {
        List<Item> items = load(prefs);
        items.removeIf(item -> item.id.equals(id));
        save(prefs, items);

        List<ClaimResponse> responses =
                loadClaimResponses(
                        prefs);

        if (responses.removeIf(
                response ->
                        response.measurementId.equals(
                                id))) {
            saveClaimResponses(
                    prefs,
                    responses);
        }
    }

    static void save(SharedPreferences prefs, List<Item> items) {
        JSONArray array = new JSONArray();
        for (Item item : items) {
            try {
                array.put(item.toJson());
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(KEY, array.toString()).apply();
    }
}
