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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
    private static final Set<String> REQUIRED_API2_KEYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "WEIGHT", "BMI", "BODY_FAT", "WATER", "MUSCLE", "LBM", "BONE",
                    "VISCERAL_FAT", "BMR", "IMPEDANCE", "IMPEDANCE_LOW", "ECW", "ICW",
                    "PROTEIN", "BCM")));

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
        public final Set<String> missingValueKeys;
        public final boolean rollbackPerformed;

        InsertResult(int apiVersion,
                     boolean measurementVerified,
                     boolean additionalValuesRequested,
                     boolean additionalValuesVerified,
                     int storedValueCount,
                     Set<String> missingValueKeys,
                     boolean rollbackPerformed) {
            this.apiVersion = apiVersion;
            this.measurementVerified = measurementVerified;
            this.additionalValuesRequested = additionalValuesRequested;
            this.additionalValuesVerified = additionalValuesVerified;
            this.storedValueCount = storedValueCount;
            this.missingValueKeys = missingValueKeys;
            this.rollbackPerformed = rollbackPerformed;
        }
    }

    public enum ExistingMeasurementStatus {
        COMPLETE,
        ABSENT,
        UNKNOWN
    }

    private static final class Verification {
        final boolean found;
        final boolean additionalValuesFound;
        final int valueCount;
        final Set<String> missingKeys;

        Verification(boolean found,
                     boolean additionalValuesFound,
                     int valueCount,
                     Set<String> missingKeys) {
            this.found = found;
            this.additionalValuesFound = additionalValuesFound;
            this.valueCount = valueCount;
            this.missingKeys = missingKeys;
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
            // Providers without /meta are legacy and unsupported.
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


    private static final class DatedWeight {
        final long timestamp;
        final float weightKg;

        DatedWeight(long timestamp, float weightKg) {
            this.timestamp = timestamp;
            this.weightKg = weightKg;
        }
    }

    /** Returns the average of the newest valid weight records, or 0 when none exist. */
    public static float readAverageRecentWeight(Context context,
                                                String authority,
                                                long userId,
                                                int limit) {
        if (authority == null || authority.isBlank() || userId < 0L || limit <= 0) return 0f;
        Uri uri = Uri.parse("content://" + authority + "/measurements/" + userId);
        List<DatedWeight> values = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{"datetime", "weight"},
                null,
                null,
                null)) {
            if (cursor == null) return 0f;
            int dateColumn = cursor.getColumnIndex("datetime");
            int weightColumn = cursor.getColumnIndex("weight");
            if (weightColumn < 0) return 0f;
            while (cursor.moveToNext()) {
                if (cursor.isNull(weightColumn)) continue;
                float weight = cursor.getFloat(weightColumn);
                if (!Float.isFinite(weight) || weight <= 0f) continue;
                long timestamp = dateColumn >= 0 && !cursor.isNull(dateColumn)
                        ? cursor.getLong(dateColumn)
                        : 0L;
                values.add(new DatedWeight(timestamp, weight));
            }
        } catch (RuntimeException ignored) {
            return 0f;
        }
        if (values.isEmpty()) return 0f;
        values.sort(Comparator.comparingLong((DatedWeight value) -> value.timestamp).reversed());
        int count = Math.min(limit, values.size());
        double sum = 0.0d;
        for (int i = 0; i < count; i++) sum += values.get(i).weightKg;
        return (float) (sum / count);
    }

    public static ExistingMeasurementStatus existingMeasurementStatus(
            Context context,
            String authority,
            long userId,
            long timestamp,
            float weightKg) {
        if (authority == null
                || authority.isBlank()
                || userId < 0L
                || timestamp <= 0L
                || !Float.isFinite(weightKg)
                || weightKg <= 0f) {
            return ExistingMeasurementStatus.UNKNOWN;
        }

        Uri uri =
                Uri.parse(
                        "content://"
                                + authority
                                + "/measurements/"
                                + userId);

        String[] projection =
                new String[]{
                        "datetime",
                        "weight",
                        "values_json"
                };

        try (Cursor cursor =
                     context.getContentResolver().query(
                             uri,
                             projection,
                             null,
                             null,
                             null)) {
            if (cursor == null) {
                return ExistingMeasurementStatus.UNKNOWN;
            }

            int dateColumn =
                    cursor.getColumnIndex("datetime");
            int weightColumn =
                    cursor.getColumnIndex("weight");
            int jsonColumn =
                    cursor.getColumnIndex("values_json");

            if (dateColumn < 0
                    || weightColumn < 0
                    || jsonColumn < 0) {
                return ExistingMeasurementStatus.UNKNOWN;
            }

            while (cursor.moveToNext()) {
                if (cursor.getLong(dateColumn)
                        != timestamp) {
                    continue;
                }

                if (Math.abs(
                                cursor.getFloat(weightColumn)
                                        - weightKg)
                        > 0.01f) {
                    return ExistingMeasurementStatus.UNKNOWN;
                }

                if (cursor.isNull(jsonColumn)) {
                    return ExistingMeasurementStatus.UNKNOWN;
                }

                JsonSummary summary =
                        summarizeValuesJson(
                                cursor.getString(jsonColumn));

                Set<String> missing =
                        new HashSet<>(
                                REQUIRED_API2_KEYS);

                missing.removeAll(
                        summary.keys);

                return missing.isEmpty()
                        ? ExistingMeasurementStatus.COMPLETE
                        : ExistingMeasurementStatus.UNKNOWN;
            }

            return ExistingMeasurementStatus.ABSENT;
        } catch (RuntimeException ignored) {
            return ExistingMeasurementStatus.UNKNOWN;
        }
    }

    /**
     * Provider API 2 receives the complete measurement including values_json.
     * Because openScale returns null even after a successful insert, the inserted timestamp
     * is queried afterwards and used as the actual success check.
     */
    public static InsertResult insertMeasurement(Context context,
                                                 String authority,
                                                 long userId,
                                                 long timestamp,
                                                 int apiVersion,
                                                 S400FinalMeasurement measurement,
                                                 S400BodyComposition.Result composition) {
        ContentValues values = new ContentValues();
        values.put("datetime", timestamp);
        values.put("weight", measurement.weightKg);
        if (composition.bodyFatPercent != null) values.put("fat", composition.bodyFatPercent);
        if (composition.totalBodyWaterPercent != null) values.put("water", composition.totalBodyWaterPercent);
        if (composition.skeletalMusclePercent != null) values.put("muscle", composition.skeletalMusclePercent);

        values.put(
                "values_json",
                buildValuesJson(context, measurement, composition));

        Uri uri = Uri.parse("content://" + authority + "/measurements/" + userId);
        ContentResolver resolver = context.getContentResolver();
        resolver.insert(uri, values);

        Verification verification = verifyMeasurement(
                resolver,
                uri,
                timestamp);
        boolean complete = verification.found
                && verification.additionalValuesFound;
        boolean rollbackPerformed = false;
        if (!complete) {
            // Keep the external write all-or-nothing. Even when verification itself
            // failed, attempt an exact timestamp rollback in case the insert landed.
            rollbackPerformed = deleteMeasurement(context, authority, userId, timestamp) > 0;
        }
        return new InsertResult(
                apiVersion,
                verification.found,
                true,
                verification.additionalValuesFound,
                verification.valueCount,
                verification.missingKeys,
                rollbackPerformed);
    }

    private static Verification verifyMeasurement(ContentResolver resolver,
                                                  Uri uri,
                                                  long timestamp) {
        String[] projection = new String[]{"datetime", "values_json"};
        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor == null) return new Verification(false, false, 0, new HashSet<>());
            int dateColumn = cursor.getColumnIndex("datetime");
            if (dateColumn < 0) return new Verification(false, false, 0, new HashSet<>());
            while (cursor.moveToNext()) {
                if (cursor.getLong(dateColumn) != timestamp) continue;
                int jsonColumn = cursor.getColumnIndex("values_json");
                if (jsonColumn < 0 || cursor.isNull(jsonColumn)) {
                    return new Verification(true, false, 0, new HashSet<>(REQUIRED_API2_KEYS));
                }
                JsonSummary summary = summarizeValuesJson(cursor.getString(jsonColumn));
                Set<String> missing = new HashSet<>(REQUIRED_API2_KEYS);
                missing.removeAll(summary.keys);
                return new Verification(true, missing.isEmpty(), summary.count, missing);
            }
        } catch (RuntimeException ignored) {
            return new Verification(false, false, 0, new HashSet<>());
        }
        return new Verification(false, false, 0, new HashSet<>());
    }

    public static int deleteMeasurement(Context context,
                                        String authority,
                                        long userId,
                                        long timestamp) {
        if (authority == null || authority.isBlank() || userId < 0L || timestamp <= 0L) return 0;
        Uri uri = Uri.parse("content://" + authority + "/measurements/" + userId);
        try {
            return context.getContentResolver().delete(
                    uri,
                    "datetime = ?",
                    new String[]{Long.toString(timestamp)});
        } catch (RuntimeException ignored) {
            return 0;
        }
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
            int validCount = 0;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String key = item.optString("key", "");
                double value = item.optDouble("value", Double.NaN);
                if (!key.isBlank() && Double.isFinite(value)) {
                    keys.add(key);
                    validCount++;
                }
            }
            return new JsonSummary(validCount, keys);
        } catch (JSONException e) {
            return new JsonSummary(0, new HashSet<>());
        }
    }

    private static String buildValuesJson(Context context,
                                          S400FinalMeasurement measurement,
                                          S400BodyComposition.Result composition) {
        JSONArray values = new JSONArray();
        try {
            add(values, 2, "BMI", "Body mass index", "", "FLOAT", composition.bmi);
            add(values, 6, "LBM", "Lean body mass", "kg", "FLOAT", composition.fatFreeMassKg);
            add(values, 7, "BONE", "Bone mass", "kg", "FLOAT", composition.boneKg);
            add(values, 12, "VISCERAL_FAT", "Visceral fat", "", "FLOAT", composition.visceralFatIndex);
            add(values, 21, "BMR", "Basal metabolic rate", "kcal", "FLOAT", composition.basalMetabolicRateKcal);
            add(values, 29, "IMPEDANCE", "Impedance high", "Ohm", "FLOAT", measurement.impedanceHigh);
            add(values, 30, "IMPEDANCE_LOW", "Impedance low", "Ohm", "FLOAT", measurement.impedanceLow);
            add(values, 31, "ECW", "Extracellular water", "%", "FLOAT", composition.extracellularWaterPercent);
            add(values, 32, "ICW", "Intracellular water", "%", "FLOAT", composition.intracellularWaterPercent);
            add(values, 33, "PROTEIN", "Protein", "%", "FLOAT", composition.proteinPercent);
            add(values, 34, "BCM", "Body cell mass", "kg", "FLOAT", composition.bodyCellMassKg);
        } catch (JSONException e) {
            throw new IllegalStateException(
                    context.getString(R.string.provider_error_values_json),
                    e);
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
