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
    private static final int REQUIRED_API_VERSION = 3;
    private static final String WEIGHT_IDENTITY = "builtin.weight";

    /*
     * API 3 identifies generic measurement types by stable identities.
     *
     * ECW, ICW and BCM are intentionally not required here. openScale 3.1.3
     * moved those S400-specific values into the ble.* namespace. Such device
     * types are created by the openScale BLE path and therefore might not yet
     * exist on a fresh installation using ScaleLauncher as the collector.
     *
     * We still send them below. When openScale already knows the types they are
     * stored; their absence must not invalidate the otherwise complete write.
     */
    private static final Set<String> REQUIRED_API3_IDENTITIES =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            Arrays.asList(
                                    WEIGHT_IDENTITY,
                                    "builtin.bmi",
                                    "builtin.body_fat",
                                    "builtin.water",
                                    "builtin.muscle",
                                    "builtin.lbm",
                                    "builtin.bone",
                                    "builtin.visceral_fat",
                                    "builtin.bmr",
                                    "builtin.impedance",
                                    "builtin.impedance_low",
                                    "builtin.protein")));

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

        public boolean supportsRequiredApi() {
            return apiVersion == REQUIRED_API_VERSION;
        }
    }

    public static final class InsertResult {
        public final int apiVersion;
        public final boolean measurementVerified;
        public final boolean additionalValuesRequested;
        public final boolean additionalValuesVerified;
        public final int storedValueCount;
        public final Set<String> missingValueIdentities;
        public final boolean rollbackPerformed;

        InsertResult(int apiVersion,
                     boolean measurementVerified,
                     boolean additionalValuesRequested,
                     boolean additionalValuesVerified,
                     int storedValueCount,
                     Set<String> missingValueIdentities,
                     boolean rollbackPerformed) {
            this.apiVersion = apiVersion;
            this.measurementVerified = measurementVerified;
            this.additionalValuesRequested = additionalValuesRequested;
            this.additionalValuesVerified = additionalValuesVerified;
            this.storedValueCount = storedValueCount;
            this.missingValueIdentities = missingValueIdentities;
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

    static Cursor requireUsersCursor(
            Cursor cursor) {
        if (cursor == null) {
            throw new IllegalStateException(
                    "openScale user query returned no cursor");
        }

        return cursor;
    }

    public static List<User> loadUsers(Context context, String authority) {
        List<User> users = new ArrayList<>();
        Uri uri = Uri.parse("content://" + authority + "/users");
        try (Cursor cursor = requireUsersCursor(
                context.getContentResolver().query(
                        uri,
                        new String[]{"_ID", "username"},
                        null,
                        null,
                        null))) {
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
    public static float readAverageRecentWeight(
            Context context,
            String authority,
            long userId,
            int limit) {
        if (authority == null
                || authority.isBlank()
                || userId < 0L
                || limit <= 0) {
            return 0f;
        }

        Uri uri =
                Uri.parse(
                        "content://"
                                + authority
                                + "/measurements/"
                                + userId);

        List<DatedWeight> values =
                new ArrayList<>();

        try (Cursor cursor =
                     context.getContentResolver().query(
                             uri,
                             new String[]{
                                     "datetime",
                                     "values_json"
                             },
                             null,
                             null,
                             null)) {
            if (cursor == null) {
                return 0f;
            }

            int dateColumn =
                    cursor.getColumnIndex(
                            "datetime");

            int jsonColumn =
                    cursor.getColumnIndex(
                            "values_json");

            if (dateColumn < 0
                    || jsonColumn < 0) {
                return 0f;
            }

            while (cursor.moveToNext()) {
                if (cursor.isNull(
                        jsonColumn)) {
                    continue;
                }

                JsonSummary summary =
                        summarizeValuesJson(
                                cursor.getString(
                                        jsonColumn));

                if (summary.weightKg == null
                        || !Double.isFinite(
                                summary.weightKg)
                        || summary.weightKg <= 0d) {
                    continue;
                }

                long timestamp =
                        !cursor.isNull(
                                dateColumn)
                                ? cursor.getLong(
                                        dateColumn)
                                : 0L;

                values.add(
                        new DatedWeight(
                                timestamp,
                                summary.weightKg.floatValue()));
            }
        } catch (RuntimeException ignored) {
            return 0f;
        }

        if (values.isEmpty()) {
            return 0f;
        }

        values.sort(
                Comparator.comparingLong(
                                (DatedWeight value) ->
                                        value.timestamp)
                        .reversed());

        int count =
                Math.min(
                        limit,
                        values.size());

        double sum =
                0.0d;

        for (int i = 0; i < count; i++) {
            sum +=
                    values.get(i).weightKg;
        }

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
                    cursor.getColumnIndex(
                            "datetime");

            int jsonColumn =
                    cursor.getColumnIndex(
                            "values_json");

            if (dateColumn < 0
                    || jsonColumn < 0) {
                return ExistingMeasurementStatus.UNKNOWN;
            }

            while (cursor.moveToNext()) {
                if (cursor.getLong(
                                dateColumn)
                        != timestamp) {
                    continue;
                }

                if (cursor.isNull(
                        jsonColumn)) {
                    return ExistingMeasurementStatus.UNKNOWN;
                }

                JsonSummary summary =
                        summarizeValuesJson(
                                cursor.getString(
                                        jsonColumn));

                Set<String> missing =
                        missingRequiredIdentities(
                                summary,
                                weightKg);

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
     * Provider API 3 receives the complete measurement including identity-based values_json.
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

        Verification verification =
                verifyMeasurement(
                        resolver,
                        uri,
                        timestamp,
                        measurement.weightKg);
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

    private static Verification verifyMeasurement(
            ContentResolver resolver,
            Uri uri,
            long timestamp,
            float expectedWeightKg) {
        String[] projection =
                new String[]{
                        "datetime",
                        "values_json"
                };

        try (Cursor cursor =
                     resolver.query(
                             uri,
                             projection,
                             null,
                             null,
                             null)) {
            if (cursor == null) {
                return new Verification(
                        false,
                        false,
                        0,
                        new HashSet<>());
            }

            int dateColumn =
                    cursor.getColumnIndex(
                            "datetime");

            int jsonColumn =
                    cursor.getColumnIndex(
                            "values_json");

            if (dateColumn < 0
                    || jsonColumn < 0) {
                return new Verification(
                        false,
                        false,
                        0,
                        new HashSet<>());
            }

            while (cursor.moveToNext()) {
                if (cursor.getLong(
                                dateColumn)
                        != timestamp) {
                    continue;
                }

                if (cursor.isNull(
                        jsonColumn)) {
                    return new Verification(
                            true,
                            false,
                            0,
                            new HashSet<>(
                                    REQUIRED_API3_IDENTITIES));
                }

                JsonSummary summary =
                        summarizeValuesJson(
                                cursor.getString(
                                        jsonColumn));

                Set<String> missing =
                        missingRequiredIdentities(
                                summary,
                                expectedWeightKg);

                return new Verification(
                        true,
                        missing.isEmpty(),
                        summary.count,
                        missing);
            }
        } catch (RuntimeException ignored) {
            return new Verification(
                    false,
                    false,
                    0,
                    new HashSet<>());
        }

        return new Verification(
                false,
                false,
                0,
                new HashSet<>());
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

    static final class JsonSummary {
        final int count;
        final Set<String> identities;
        final Double weightKg;

        JsonSummary(
                int count,
                Set<String> identities,
                Double weightKg) {
            this.count =
                    count;

            this.identities =
                    identities;

            this.weightKg =
                    weightKg;
        }
    }

    static JsonSummary summarizeValuesJson(
            String json) {
        if (json == null
                || json.isBlank()) {
            return new JsonSummary(
                    0,
                    new HashSet<>(),
                    null);
        }

        try {
            Object root =
                    new JSONTokener(
                            json)
                            .nextValue();

            JSONArray array;

            if (root instanceof JSONArray) {
                array =
                        (JSONArray) root;
            } else if (root instanceof JSONObject) {
                JSONObject object =
                        (JSONObject) root;

                array =
                        object.optJSONArray(
                                "values");

                if (array == null) {
                    return new JsonSummary(
                            0,
                            new HashSet<>(),
                            null);
                }
            } else {
                return new JsonSummary(
                        0,
                        new HashSet<>(),
                        null);
            }

            Set<String> identities =
                    new HashSet<>();

            int validCount =
                    0;

            Double weightKg =
                    null;

            for (int i = 0; i < array.length(); i++) {
                JSONObject item =
                        array.optJSONObject(
                                i);

                if (item == null) {
                    continue;
                }

                String identity =
                        item.optString(
                                "identity",
                                "");

                double value =
                        item.optDouble(
                                "value",
                                Double.NaN);

                if (identity.isBlank()
                        || !Double.isFinite(
                                value)) {
                    continue;
                }

                identities.add(
                        identity);

                validCount++;

                if (WEIGHT_IDENTITY.equals(
                        identity)) {
                    weightKg =
                            value;
                }
            }

            return new JsonSummary(
                    validCount,
                    identities,
                    weightKg);
        } catch (JSONException e) {
            return new JsonSummary(
                    0,
                    new HashSet<>(),
                    null);
        }
    }

    static Set<String> missingRequiredIdentities(
            JsonSummary summary,
            float expectedWeightKg) {
        Set<String> missing =
                new HashSet<>(
                        REQUIRED_API3_IDENTITIES);

        missing.removeAll(
                summary.identities);

        if (summary.weightKg == null
                || !Double.isFinite(
                        summary.weightKg)
                || Math.abs(
                                summary.weightKg
                                        - expectedWeightKg)
                        > 0.01d) {
            missing.add(
                    WEIGHT_IDENTITY);
        }

        return missing;
    }

    private static String buildValuesJson(
            Context context,
            S400FinalMeasurement measurement,
            S400BodyComposition.Result composition) {
        JSONArray values =
                new JSONArray();

        try {
            add(
                    values,
                    WEIGHT_IDENTITY,
                    "Weight",
                    "kg",
                    "FLOAT",
                    false,
                    measurement.weightKg);

            add(
                    values,
                    "builtin.body_fat",
                    "Body fat",
                    "%",
                    "FLOAT",
                    false,
                    composition.bodyFatPercent);

            add(
                    values,
                    "builtin.water",
                    "Body water",
                    "%",
                    "FLOAT",
                    false,
                    composition.totalBodyWaterPercent);

            add(
                    values,
                    "builtin.muscle",
                    "Muscle",
                    "%",
                    "FLOAT",
                    false,
                    composition.skeletalMusclePercent);

            add(
                    values,
                    "builtin.bmi",
                    "Body mass index",
                    "",
                    "FLOAT",
                    true,
                    composition.bmi);

            add(
                    values,
                    "builtin.lbm",
                    "Lean body mass",
                    "kg",
                    "FLOAT",
                    false,
                    composition.fatFreeMassKg);

            add(
                    values,
                    "builtin.bone",
                    "Bone mass",
                    "kg",
                    "FLOAT",
                    false,
                    composition.boneKg);

            add(
                    values,
                    "builtin.visceral_fat",
                    "Visceral fat",
                    "",
                    "FLOAT",
                    false,
                    composition.visceralFatIndex);

            add(
                    values,
                    "builtin.bmr",
                    "Basal metabolic rate",
                    "kcal",
                    "FLOAT",
                    true,
                    composition.basalMetabolicRateKcal);

            add(
                    values,
                    "builtin.impedance",
                    "Impedance high",
                    "Ohm",
                    "FLOAT",
                    false,
                    measurement.impedanceHigh);

            add(
                    values,
                    "builtin.impedance_low",
                    "Impedance low",
                    "Ohm",
                    "FLOAT",
                    false,
                    measurement.impedanceLow);

            add(
                    values,
                    "ble.ecw",
                    "Extracellular water",
                    "%",
                    "FLOAT",
                    false,
                    composition.extracellularWaterPercent);

            add(
                    values,
                    "ble.icw",
                    "Intracellular water",
                    "%",
                    "FLOAT",
                    false,
                    composition.intracellularWaterPercent);

            add(
                    values,
                    "builtin.protein",
                    "Protein",
                    "%",
                    "FLOAT",
                    false,
                    composition.proteinPercent);

            add(
                    values,
                    "ble.bcm",
                    "Body cell mass",
                    "kg",
                    "FLOAT",
                    false,
                    composition.bodyCellMassKg);
        } catch (JSONException e) {
            throw new IllegalStateException(
                    context.getString(
                            R.string.provider_error_values_json),
                    e);
        }

        return values.toString();
    }

    static void add(
            JSONArray array,
            String identity,
            String name,
            String unit,
            String inputType,
            boolean isDerived,
            Float value)
            throws JSONException {
        if (value == null
                || Float.isNaN(
                        value)
                || Float.isInfinite(
                        value)) {
            return;
        }

        JSONObject item =
                new JSONObject();

        item.put(
                "identity",
                identity);

        item.put(
                "name",
                name);

        item.put(
                "unit",
                unit);

        item.put(
                "inputType",
                inputType);

        item.put(
                "isDerived",
                isDerived);

        item.put(
                "value",
                value.doubleValue());

        array.put(
                item);
    }

}
