package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class RemotePendingMeasurementStore {
    private static final String PREFS = "remote_pending_measurements_v1";
    private static final String KEY = "items";
    private static final int MAX_ITEMS = 10;

    static final class Item {
        final String measurementId;
        final String collectorDeviceId;
        final String scaleMac;
        final long timestampMs;
        final float weightKg;
        final float impedanceHigh;
        final float impedanceLow;
        final Integer scaleProfileId;
        final List<String> candidateProfileIds;
        final long receivedAtMs;

        Item(String measurementId,
             String collectorDeviceId,
             String scaleMac,
             long timestampMs,
             float weightKg,
             float impedanceHigh,
             float impedanceLow,
             Integer scaleProfileId,
             List<String> candidateProfileIds,
             long receivedAtMs) {
            this.measurementId = measurementId == null ? "" : measurementId;
            this.collectorDeviceId = collectorDeviceId == null ? "" : collectorDeviceId;
            this.scaleMac = scaleMac == null ? "" : scaleMac.trim().toUpperCase(java.util.Locale.ROOT);
            this.timestampMs = timestampMs;
            this.weightKg = weightKg;
            this.impedanceHigh = impedanceHigh;
            this.impedanceLow = impedanceLow;
            this.scaleProfileId = scaleProfileId;
            this.candidateProfileIds = sanitizeCandidateProfileIds(candidateProfileIds);
            this.receivedAtMs = receivedAtMs;
        }

        boolean isValid() {
            return !measurementId.isBlank()
                    && measurementId.length() <= 200
                    && PeerTrustStore.isValidDeviceId(collectorDeviceId)
                    && S400GattProtocol.isValidMacAddress(scaleMac)
                    && timestampMs > 0L
                    && Float.isFinite(weightKg) && weightKg > 0f
                    && Float.isFinite(impedanceHigh) && impedanceHigh > 0f
                    && Float.isFinite(impedanceLow) && impedanceLow > 0f
                    && !candidateProfileIds.isEmpty()
                    && receivedAtMs > 0L;
        }

        JSONObject toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("measurementId", measurementId);
            object.put("collectorDeviceId", collectorDeviceId);
            object.put("scaleMac", scaleMac);
            object.put("timestampMs", timestampMs);
            object.put("weightKg", weightKg);
            object.put("impedanceHigh", impedanceHigh);
            object.put("impedanceLow", impedanceLow);
            object.put("receivedAtMs", receivedAtMs);
            if (scaleProfileId != null) object.put("scaleProfileId", scaleProfileId);

            JSONArray candidates = new JSONArray();
            for (String profileId : candidateProfileIds) candidates.put(profileId);
            object.put("candidateProfileIds", candidates);
            return object;
        }

        static Item fromJson(JSONObject object) {
            if (object == null) return null;

            List<String> candidates = new ArrayList<>();
            JSONArray candidateArray = object.optJSONArray("candidateProfileIds");
            if (candidateArray != null) {
                for (int index = 0; index < candidateArray.length(); index++) {
                    String profileId = candidateArray.optString(index, "");
                    if (!profileId.isBlank()) candidates.add(profileId);
                }
            }

            Integer scaleProfileId =
                    object.has("scaleProfileId") && !object.isNull("scaleProfileId")
                            ? object.optInt("scaleProfileId")
                            : null;

            Item item = new Item(
                    object.optString("measurementId", ""),
                    object.optString("collectorDeviceId", ""),
                    object.optString("scaleMac", ""),
                    object.optLong("timestampMs", 0L),
                    (float) object.optDouble("weightKg", Double.NaN),
                    (float) object.optDouble("impedanceHigh", Double.NaN),
                    (float) object.optDouble("impedanceLow", Double.NaN),
                    scaleProfileId,
                    candidates,
                    object.optLong("receivedAtMs", 0L));

            return item.isValid() ? item : null;
        }
    }

    private RemotePendingMeasurementStore() {}

    static List<Item> load(Context context) {
        List<Item> result = new ArrayList<>();
        String encoded = prefs(context).getString(KEY, "");
        if (encoded == null || encoded.isBlank()) return result;

        try {
            JSONArray array = new JSONArray(encoded);
            for (int index = 0; index < array.length(); index++) {
                Item item = Item.fromJson(array.optJSONObject(index));
                if (item != null) result.add(item);
            }
        } catch (JSONException ignored) {
        }
        return result;
    }

    static Item find(Context context, String measurementId) {
        if (measurementId == null || measurementId.isBlank()) return null;
        for (Item item : load(context)) {
            if (measurementId.equals(item.measurementId)) return item;
        }
        return null;
    }

    static boolean upsert(Context context,
                          PeerTrustStore.Peer collector,
                          PeerMeasurementPayload payload,
                          List<String> localCandidateProfileIds) {
        if (collector == null || payload == null || !payload.requiresClaim) return false;

        Item incoming = new Item(
                payload.measurementId,
                collector.deviceId,
                payload.scaleMac,
                payload.timestampMs,
                payload.weightKg,
                payload.impedanceHigh,
                payload.impedanceLow,
                payload.scaleProfileId,
                localCandidateProfileIds,
                System.currentTimeMillis());

        if (!incoming.isValid()) return false;

        List<Item> items = load(context);
        for (int index = 0; index < items.size(); index++) {
            Item existing = items.get(index);
            if (!incoming.measurementId.equals(existing.measurementId)) continue;

            if (!incoming.collectorDeviceId.equals(existing.collectorDeviceId)) {
                return false;
            }

            items.set(index, incoming);
            save(context, items);
            return true;
        }

        items.add(incoming);
        while (items.size() > MAX_ITEMS) items.remove(0);
        save(context, items);
        return true;
    }

    static boolean remove(Context context, String measurementId) {
        if (measurementId == null || measurementId.isBlank()) return false;
        List<Item> items = load(context);
        boolean removed = items.removeIf(
                item -> measurementId.equals(item.measurementId));
        if (removed) save(context, items);
        return removed;
    }

    static int removeCollector(Context context, String collectorDeviceId) {
        if (!PeerTrustStore.isValidDeviceId(collectorDeviceId)) return 0;
        List<Item> items = load(context);
        int before = items.size();
        items.removeIf(item -> collectorDeviceId.equals(item.collectorDeviceId));
        int removed = before - items.size();
        if (removed > 0) save(context, items);
        return removed;
    }

    private static List<String> sanitizeCandidateProfileIds(List<String> ids) {
        Set<String> result = new LinkedHashSet<>();
        if (ids != null) {
            for (String id : ids) {
                if (UserProfile.isValidHouseholdProfileId(id)) result.add(id);
            }
        }
        return new ArrayList<>(result);
    }

    private static void save(Context context, List<Item> items) {
        JSONArray array = new JSONArray();
        for (Item item : items) {
            if (item == null || !item.isValid()) continue;
            try {
                array.put(item.toJson());
            } catch (JSONException ignored) {
            }
        }
        prefs(context).edit().putString(KEY, array.toString()).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
