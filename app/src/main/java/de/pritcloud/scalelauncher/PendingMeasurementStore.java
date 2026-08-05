package de.pritcloud.scalelauncher;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PendingMeasurementStore {
    private static final String KEY = "pending_measurements_json";
    private static final int MAX_ITEMS = 10;

    static final class Item {
        final String id;
        final float weightKg;
        final float impedanceHigh;
        final Float impedanceLow;
        final boolean timedOut;
        final long timestampMs;
        final String reason;

        Item(String id,
             float weightKg,
             float impedanceHigh,
             Float impedanceLow,
             boolean timedOut,
             long timestampMs,
             String reason) {
            this.id = id;
            this.weightKg = weightKg;
            this.impedanceHigh = impedanceHigh;
            this.impedanceLow = impedanceLow;
            this.timedOut = timedOut;
            this.timestampMs = timestampMs;
            this.reason = reason;
        }

        S400Aggregator.Finalized toMeasurement() {
            return new S400Aggregator.Finalized(
                    weightKg,
                    impedanceHigh,
                    impedanceLow,
                    timedOut,
                    timestampMs);
        }

        JSONObject toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("id", id);
            object.put("weightKg", weightKg);
            object.put("impedanceHigh", impedanceHigh);
            if (impedanceLow != null) object.put("impedanceLow", impedanceLow);
            object.put("timedOut", timedOut);
            object.put("timestampMs", timestampMs);
            object.put("reason", reason);
            return object;
        }

        static Item fromJson(JSONObject object) {
            Float low = object.has("impedanceLow")
                    ? (float) object.optDouble("impedanceLow", 0.0d)
                    : null;
            return new Item(
                    object.optString("id", ""),
                    (float) object.optDouble("weightKg", 0.0d),
                    (float) object.optDouble("impedanceHigh", 0.0d),
                    low,
                    object.optBoolean("timedOut", false),
                    object.optLong("timestampMs", System.currentTimeMillis()),
                    object.optString("reason", ""));
        }
    }

    private PendingMeasurementStore() {}

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
                    S400Aggregator.Finalized measurement,
                    String reason) {
        List<Item> items = load(prefs);
        Item item = new Item(
                UUID.randomUUID().toString(),
                measurement.weightKg,
                measurement.impedanceHigh,
                measurement.impedanceLow,
                measurement.timedOut,
                measurement.timestampMs,
                reason);
        items.add(item);
        while (items.size() > MAX_ITEMS) items.remove(0);
        save(prefs, items);
        return item;
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
