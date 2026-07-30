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

import java.util.ArrayList;
import java.util.List;

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
        User(long id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name + " (#" + id + ")"; }
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

    public static List<User> loadUsers(Context context, String authority) {
        List<User> users = new ArrayList<>();
        Uri uri = Uri.parse("content://" + authority + "/users");
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{"_ID", "username"}, null, null, null)) {
            if (cursor == null) return users;
            int idColumn = cursor.getColumnIndexOrThrow("_ID");
            int nameColumn = cursor.getColumnIndexOrThrow("username");
            while (cursor.moveToNext()) users.add(new User(cursor.getLong(idColumn), cursor.getString(nameColumn)));
        }
        return users;
    }

    /**
     * Inserts the four legacy provider fields plus all S400-specific values via values_json.
     * The JSON key names and canonical units follow openScale's provider API.
     */
    public static boolean insertMeasurement(Context context,
                                            String authority,
                                            long userId,
                                            long timestamp,
                                            S400Aggregator.Finalized measurement,
                                            S400BodyComposition.Result composition) {
        ContentValues values = new ContentValues();
        values.put("datetime", timestamp);
        values.put("weight", measurement.weightKg);
        if (composition.bodyFatPercent != null) values.put("fat", composition.bodyFatPercent);
        if (composition.totalBodyWaterPercent != null) values.put("water", composition.totalBodyWaterPercent);
        if (composition.skeletalMusclePercent != null) values.put("muscle", composition.skeletalMusclePercent);
        values.put("values_json", buildValuesJson(measurement, composition));

        Uri uri = Uri.parse("content://" + authority + "/measurements/" + userId);
        ContentResolver resolver = context.getContentResolver();
        resolver.insert(uri, values);
        // openScale currently returns null for compatibility even after a successful insert.
        return true;
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
