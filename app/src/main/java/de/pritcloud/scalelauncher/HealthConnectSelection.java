package de.pritcloud.scalelauncher;

import android.content.SharedPreferences;

/** Selected Health Connect values. */
final class HealthConnectSelection {
    static final String PREF_WEIGHT = "hc_value_weight";
    static final String PREF_BODY_FAT = "hc_value_body_fat";
    static final String PREF_BODY_WATER = "hc_value_body_water";
    static final String PREF_BONE_MASS = "hc_value_bone_mass";
    static final String PREF_LEAN_BODY_MASS = "hc_value_lean_body_mass";
    static final String PREF_BMR = "hc_value_bmr";
    static final String PREF_BMI = "hc_value_bmi";
    private static final String LEGACY_PREF_HEART_RATE = "hc_value_heart_rate";

    final boolean weight;
    final boolean bodyFat;
    final boolean bodyWater;
    final boolean boneMass;
    final boolean leanBodyMass;
    final boolean basalMetabolicRate;
    final boolean bmi;

    HealthConnectSelection(boolean weight,
                           boolean bodyFat,
                           boolean bodyWater,
                           boolean boneMass,
                           boolean leanBodyMass,
                           boolean basalMetabolicRate,
                           boolean bmi) {
        this.weight = weight;
        this.bodyFat = bodyFat;
        this.bodyWater = bodyWater;
        this.boneMass = boneMass;
        this.leanBodyMass = leanBodyMass;
        this.basalMetabolicRate = basalMetabolicRate;
        this.bmi = bmi;
    }

    static HealthConnectSelection fromPreferences(SharedPreferences prefs) {
        return new HealthConnectSelection(
                prefs.getBoolean(PREF_WEIGHT, true),
                prefs.getBoolean(PREF_BODY_FAT, true),
                prefs.getBoolean(PREF_BODY_WATER, true),
                prefs.getBoolean(PREF_BONE_MASS, true),
                prefs.getBoolean(PREF_LEAN_BODY_MASS, true),
                prefs.getBoolean(PREF_BMR, true),
                prefs.getBoolean(PREF_BMI, true));
    }

    void save(SharedPreferences.Editor editor) {
        editor.putBoolean(PREF_WEIGHT, weight)
                .putBoolean(PREF_BODY_FAT, bodyFat)
                .putBoolean(PREF_BODY_WATER, bodyWater)
                .putBoolean(PREF_BONE_MASS, boneMass)
                .putBoolean(PREF_LEAN_BODY_MASS, leanBodyMass)
                .putBoolean(PREF_BMR, basalMetabolicRate)
                .putBoolean(PREF_BMI, bmi)
                .remove(LEGACY_PREF_HEART_RATE);
    }

    int count() {
        int count = 0;
        if (weight) count++;
        if (bodyFat) count++;
        if (bodyWater) count++;
        if (boneMass) count++;
        if (leanBodyMass) count++;
        if (basalMetabolicRate) count++;
        if (bmi) count++;
        return count;
    }

    String summary() {
        StringBuilder out = new StringBuilder();
        append(out, weight, "Gewicht");
        append(out, bodyFat, "Körperfett");
        append(out, bodyWater, "Körperwasser");
        append(out, boneMass, "Knochenmasse");
        append(out, leanBodyMass, "fettfreie Masse");
        append(out, basalMetabolicRate, "Grundumsatz");
        append(out, bmi, "BMI-Grundlage");
        return out.length() == 0 ? "keine" : out.toString();
    }

    private static void append(StringBuilder out, boolean enabled, String label) {
        if (!enabled) return;
        if (out.length() > 0) out.append(", ");
        out.append(label);
    }
}
