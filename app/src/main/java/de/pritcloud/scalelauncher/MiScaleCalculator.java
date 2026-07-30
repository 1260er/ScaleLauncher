package de.pritcloud.scalelauncher;

public final class MiScaleCalculator {
    private final int sex; // 1 male, 0 female
    private final int age;
    private final float heightCm;

    public MiScaleCalculator(int sex, int age, float heightCm) {
        this.sex = sex;
        this.age = age;
        this.heightCm = heightCm;
    }

    private float lbmCoefficient(float weight, float impedance) {
        float lbm = (heightCm * 9.058f / 100f) * (heightCm / 100f);
        lbm += weight * 0.32f + 12.226f;
        lbm -= impedance * 0.0068f;
        lbm -= age * 0.0542f;
        return lbm;
    }

    public float bodyFat(float weight, float impedance) {
        float lbmSub = 0.8f;
        if (sex == 0 && age <= 49) lbmSub = 9.25f;
        else if (sex == 0) lbmSub = 7.25f;
        float coefficient = 1f;
        if (sex == 1 && weight < 61f) coefficient = 0.98f;
        else if (sex == 0 && weight > 60f) {
            coefficient = 0.96f;
            if (heightCm > 160f) coefficient *= 1.03f;
        } else if (sex == 0 && weight < 50f) {
            coefficient = 1.02f;
            if (heightCm > 160f) coefficient *= 1.03f;
        }
        float fat = (1f - (((lbmCoefficient(weight, impedance) - lbmSub) * coefficient) / weight)) * 100f;
        return fat > 63f ? 75f : fat;
    }

    public float water(float weight, float impedance) {
        float value = (100f - bodyFat(weight, impedance)) * 0.7f;
        return (value < 50f ? 1.02f : 0.98f) * value;
    }

    public float muscle(float weight, float impedance) {
        if (weight <= 0f) return 0f;
        float h2OverR = (heightCm * heightCm) / impedance;
        float smmKg = 0.401f * h2OverR + 3.825f * sex - 0.071f * age + 5.102f;
        return Math.max(10f, Math.min(60f, smmKg / weight * 100f));
    }
}
