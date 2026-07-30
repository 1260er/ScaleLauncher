package de.pritcloud.scalelauncher;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class OpenScaleProvider {
    private static final String[] AUTHORITIES = {
            "com.health.openscale.provider",
            "com.health.openscale.oss.provider",
            "com.health.openscale.beta.provider",
            "com.health.openscale.debug.provider"
    };

    public static final class User {
        public final long id;
        public final String name;

        User(long id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override public String toString() {
            return name + " (#" + id + ")";
        }
    }

    public static final class Meta {
        public final int apiVersion;
        public final int appVersionCode;

        Meta(int apiVersion, int appVersionCode) {
            this.apiVersion = apiVersion;
            this.appVersionCode = appVersionCode;
        }

        public boolean supportsGenericValues() {
            return apiVersion >= 2;
        }
    }

    public static final class InsertResult {
        public final int apiVersion;
        public final boolean measurementVerified;
        public final boolean additionalValuesRequested;
        public final boolean additionalValuesVerified;
        public final int storedValueCount;

        InsertResult(int apiVersion,
                     boolean measurementVerified,
                     boolean additionalValuesRequested,
                     boolean additionalValuesVerified,
                     int storedValueCount) {
            this.apiVersion = apiVersion;
            this.measurementVerified = measurementVerified;
            this.additionalValuesRequested = additionalValuesRequested;
            this.additionalValuesVerified = additionalValuesVerified;
            this.storedValueCount = storedValueCount;
        }
    }

    private static final class Verification {
        final boolean found;
        final boolean additionalValuesFound;
        final int valueCount;

        Verification(boolean found, boolean additionalValuesFound, int valueCount) {
            this.found = found;
            this.additionalValuesFound = additionalValuesFound;
            this.valueCount = valueCount;
        }
    }

    private OpenScaleProvider() {}

    public static String findAuthority(Context context) {
        for (String authority : AUTHORITIES) {
            ProviderInfo info = context.getPackageManager().resolveContentProvider(authority, 0);
            if (info != null) return authority;
        }
        return null;
    }

    public static String permissionForAuthority(String authority) {
        if (authority == null || !authority.endsWith(".provider")) return null;
        return authority.substring(0, authority.length() - ".provider".length()) + ".READ_WRITE_DATA";
    }

    public static Meta readMeta(Context context, String authority) {
        if (authority == null || authority.isBlank()) return new Meta(1, -1);
        Uri uri = Uri.parse("content://" + authority + "/meta");
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{"apiVersion", "versionCode"},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int apiColumn = cursor.getColumnIndex("apiVersion");
                int versionColumn = cursor.getColumnIndex("versionCode");
                int apiVersion = apiColumn >= 0 ? cursor.getInt(apiColumn) : 1;
                int versionCode = versionColumn >= 0 ? cursor.getInt(versionColumn) : -1;
                return new Meta(Math.max(1, apiVersion), versionCode);
            }
        } catch (RuntimeException ignored) {
            // Older compatible providers may not expose /meta. Treat them as API 1.
        }
        return new Meta(1, -1);
    }

    public static List<User> loadUsers(Context context, String authority) {
        List<User> users = new ArrayList<>();
        Uri uri = Uri.parse("content://" + authority + "/users");
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{"_ID", "username"},
                null,
                null,
                null)) {
            if (cursor == null) return users;
            int idColumn = cursor.getColumnIndexOrThrow("_ID");
            int nameColumn = cursor.getColumnIndexOrThrow("username");
            while (cursor.moveToNext()) {
                users.add(new User(cursor.getLong(idColumn), cursor.getString(nameColumn)));
            }
        }
        return users;
    }

    /**
     * API 1 receives the four legacy fields. API 2 additionally receives values_json.
     * Because openScale returns null even after a successful insert, the inserted timestamp
     * is queried afterwards and used as the actual success check.
     */
    public static InsertResult insertMeasurement(Context context,
                                                 String authority,
                                                 long userId,
                                                 long timestamp,
                                                 int apiVersion,
                                                 S400Aggregator.Finalized measurement,
                                                 S400BodyComposition.Result composition) {
        ContentValues values = new ContentValues();
        values.put("datetime", timestamp);
        values.put("weight", measurement.weightKg);
        if (composition.bodyFatPercent != null) values.put("fat", composition.bodyFatPercent);
        if (composition.totalBodyWaterPercent != null) values.put("water", composition.totalBodyWaterPercent);
        if (composition.skeletalMusclePercent != null) values.put("muscle", composition.skeletalMusclePercent);

        boolean requestAdditionalValues = apiVersion >= 2;
        if (requestAdditionalValues) {
            values.put("values_json", buildValuesJson(measurement, composition));
        }

        Uri uri = Uri.parse("content://" + authority + "/measurements/" + userId);
        ContentResolver resolver = context.getContentResolver();
        resolver.insert(uri, values);

        Verification verification = verifyMeasurement(
                resolver,
                uri,
                timestamp,
                apiVersion);
        return new InsertResult(
                apiVersion,
                verification.found,
                requestAdditionalValues,
                requestAdditionalValues && verification.additionalValuesFound,
                verification.valueCount);
    }

    private static Verification verifyMeasurement(ContentResolver resolver,
                                                  Uri uri,
                                                  long timestamp,
                                                  int apiVersion) {
        String[] projection = apiVersion >= 2
                ? new String[]{"datetime", "values_json"}
                : new String[]{"datetime", "weight", "fat", "water", "muscle"};
        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor == null) return new Verification(false, false, 0);
            int dateColumn = cursor.getColumnIndex("datetime");
            if (dateColumn < 0) return new Verification(false, false, 0);
            while (cursor.moveToNext()) {
                if (cursor.getLong(dateColumn) != timestamp) continue;
                if (apiVersion < 2) {
                    int count = 0;
                    for (String column : new String[]{"weight", "fat", "water", "muscle"}) {
                        int index = cursor.getColumnIndex(column);
                        if (index >= 0 && !cursor.isNull(index)) count++;
                    }
                    return new Verification(true, false, count);
                }

                int jsonColumn = cursor.getColumnIndex("values_json");
                if (jsonColumn < 0 || cursor.isNull(jsonColumn)) {
                    return new Verification(true, false, 0);
                }
                JsonSummary summary = summarizeValuesJson(cursor.getString(jsonColumn));
                boolean hasAdditional = summary.keys.contains("BONE")
                        || summary.keys.contains("PROTEIN")
                        || summary.keys.contains("HEART_RATE")
                        || summary.count > 4;
                return new Verification(true, hasAdditional, summary.count);
            }
        } catch (RuntimeException ignored) {
            return new Verification(false, false, 0);
        }
        return new Verification(false, false, 0);
    }

    private static final class JsonSummary {
        final int count;
        final Set<String> keys;

        JsonSummary(int count, Set<String> keys) {
            this.count = count;
            this.keys = keys;
        }
    }

    private static JsonSummary summarizeValuesJson(String json) {
        if (json == null || json.isBlank()) return new JsonSummary(0, new HashSet<>());
        try {
            Object root = new JSONTokener(json).nextValue();
            JSONArray array;
            if (root instanceof JSONArray) {
                array = (JSONArray) root;
            } else if (root instanceof JSONObject) {
                JSONObject object = (JSONObject) root;
                array = object.optJSONArray("values");
                if (array == null) return new JsonSummary(0, new HashSet<>());
            } else {
                return new JsonSummary(0, new HashSet<>());
            }

            Set<String> keys = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String key = item.optString("key", "");
                if (!key.isBlank()) keys.add(key);
            }
            return new JsonSummary(array.length(), keys);
        } catch (JSONException e) {
            return new JsonSummary(0, new HashSet<>());
        }
    }

    private static String buildValuesJson(S400Aggregator.Finalized measurement,
                                          S400BodyComposition.Result composition) {
        JSONArray values = new JSONArray();
        try {
            add(values, 2, "BMI", "Body mass index", "", "FLOAT", composition.bmi);
            add(values, 6, "LBM", "Lean body mass", "kg", "FLOAT", composition.fatFreeMassKg);
            add(values, 7, "BONE", "Bone mass", "kg", "FLOAT", composition.boneKg);
            add(values, 12, "VISCERAL_FAT", "Visceral fat", "", "FLOAT", composition.visceralFatIndex);
            add(values, 21, "BMR", "Basal metabolic rate", "kcal", "FLOAT", composition.basalMetabolicRateKcal);
            add(values, 23, "HEART_RATE", "Heart rate", "/min", "INT",
                    measurement.heartRate == null ? null : measurement.heartRate.floatValue());
            add(values, 29, "IMPEDANCE", "Impedance high", "Ohm", "FLOAT", measurement.impedanceHigh);
            add(values, 30, "IMPEDANCE_LOW", "Impedance low", "Ohm", "FLOAT", measurement.impedanceLow);
            add(values, 31, "ECW", "Extracellular water", "%", "FLOAT", composition.extracellularWaterPercent);
            add(values, 32, "ICW", "Intracellular water", "%", "FLOAT", composition.intracellularWaterPercent);
            add(values, 33, "PROTEIN", "Protein", "%", "FLOAT", composition.proteinPercent);
            add(values, 34, "BCM", "Body cell mass", "kg", "FLOAT", composition.bodyCellMassKg);
        } catch (JSONException e) {
            throw new IllegalStateException("values_json konnte nicht erstellt werden", e);
        }
        return values.toString();
    }

    private static void add(JSONArray array,
                            int typeId,
                            String key,
                            String name,
                            String unit,
                            String inputType,
                            Float value) throws JSONException {
        if (value == null || Float.isNaN(value) || Float.isInfinite(value)) return;
        JSONObject item = new JSONObject();
        item.put("typeId", typeId);
        item.put("key", key);
        item.put("name", name);
        item.put("unit", unit);
        item.put("inputType", inputType);
        item.put("isDerived", false);
        item.put("value", value.doubleValue());
        array.put(item);
    }
}
