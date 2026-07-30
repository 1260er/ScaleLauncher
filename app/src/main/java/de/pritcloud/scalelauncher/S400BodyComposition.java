package de.pritcloud.scalelauncher;

/**
 * Local body-composition pipeline for the Xiaomi S400.
 *
 * Java port of openScale's GPLv3 S400BodyComposition implementation
 * (copyright 2026 Dany Mestas). No network access is used.
 */
public final class S400BodyComposition {
    public static final float FOOT_TO_FOOT_CORRECTION = 1.10f;

    public enum Reliability { OK, APPROXIMATE, UNRELIABLE, NOT_AVAILABLE }

    public static final class Inputs {
        public final int age;
        public final boolean sexMale;
        public final float heightCm;
        public final float weightKg;
        public final float impedanceHighRaw;
        public final float impedanceLowRaw;

        public Inputs(int age, boolean sexMale, float heightCm, float weightKg,
                      float impedanceHighRaw, float impedanceLowRaw) {
            this.age = age;
            this.sexMale = sexMale;
            this.heightCm = heightCm;
            this.weightKg = weightKg;
            this.impedanceHighRaw = impedanceHighRaw;
            this.impedanceLowRaw = impedanceLowRaw;
        }
    }

    public static final class Result {
        public final float weightKg;
        public final float bmi;
        public final Float totalBodyWaterKg;
        public final Float totalBodyWaterPercent;
        public final Float extracellularWaterKg;
        public final Float extracellularWaterPercent;
        public final Float intracellularWaterKg;
        public final Float intracellularWaterPercent;
        public final Float extracellularToTotalWaterRatio;
        public final Float fatFreeMassKg;
        public final Float fatFreeMassPercent;
        public final Float bodyFatKg;
        public final Float bodyFatPercent;
        public final Float skeletalMuscleKg;
        public final Float skeletalMusclePercent;
        public final Float boneKg;
        public final Float visceralFatIndex;
        public final Float basalMetabolicRateKcal;
        public final Float bodyCellMassKg;
        public final Float proteinKg;
        public final Float proteinPercent;
        public final Float softLeanMassKg;
        public final Float phaseAngleDegrees;
        public final Reliability reliability;
        public final boolean impedanceLabelsSwapped;

        private Result(float weightKg,
                       float bmi,
                       Float totalBodyWaterKg,
                       Float totalBodyWaterPercent,
                       Float extracellularWaterKg,
                       Float extracellularWaterPercent,
                       Float intracellularWaterKg,
                       Float intracellularWaterPercent,
                       Float extracellularToTotalWaterRatio,
                       Float fatFreeMassKg,
                       Float fatFreeMassPercent,
                       Float bodyFatKg,
                       Float bodyFatPercent,
                       Float skeletalMuscleKg,
                       Float skeletalMusclePercent,
                       Float boneKg,
                       Float visceralFatIndex,
                       Float basalMetabolicRateKcal,
                       Float bodyCellMassKg,
                       Float proteinKg,
                       Float proteinPercent,
                       Float softLeanMassKg,
                       Float phaseAngleDegrees,
                       Reliability reliability,
                       boolean impedanceLabelsSwapped) {
            this.weightKg = weightKg;
            this.bmi = bmi;
            this.totalBodyWaterKg = totalBodyWaterKg;
            this.totalBodyWaterPercent = totalBodyWaterPercent;
            this.extracellularWaterKg = extracellularWaterKg;
            this.extracellularWaterPercent = extracellularWaterPercent;
            this.intracellularWaterKg = intracellularWaterKg;
            this.intracellularWaterPercent = intracellularWaterPercent;
            this.extracellularToTotalWaterRatio = extracellularToTotalWaterRatio;
            this.fatFreeMassKg = fatFreeMassKg;
            this.fatFreeMassPercent = fatFreeMassPercent;
            this.bodyFatKg = bodyFatKg;
            this.bodyFatPercent = bodyFatPercent;
            this.skeletalMuscleKg = skeletalMuscleKg;
            this.skeletalMusclePercent = skeletalMusclePercent;
            this.boneKg = boneKg;
            this.visceralFatIndex = visceralFatIndex;
            this.basalMetabolicRateKcal = basalMetabolicRateKcal;
            this.bodyCellMassKg = bodyCellMassKg;
            this.proteinKg = proteinKg;
            this.proteinPercent = proteinPercent;
            this.softLeanMassKg = softLeanMassKg;
            this.phaseAngleDegrees = phaseAngleDegrees;
            this.reliability = reliability;
            this.impedanceLabelsSwapped = impedanceLabelsSwapped;
        }
    }

    private static final float K_ECW_M = (float) (Math.cbrt(4.3 * 4.3 * 40.5 * 40.5 / 1.05) / 100.0);
    private static final float K_ECW_F = (float) (Math.cbrt(4.3 * 4.3 * 39.0 * 39.0 / 1.05) / 100.0);

    private S400BodyComposition() {}

    /** Uses the same defaults as openScale: MI legacy bone and Cunningham 1991 BMR. */
    public static Result compute(Inputs inputs) {
        float weight = inputs.weightKg;
        float height = inputs.heightCm;
        float heightM = height / 100f;
        float bmi = height > 0f ? weight / (heightM * heightM) : 0f;

        if (!isWithinValidationRange(inputs, bmi)) {
            return notAvailable(weight, bmi);
        }

        float impedanceHigh = inputs.impedanceHighRaw;
        float impedanceLow = inputs.impedanceLowRaw;
        boolean labelsSwapped = impedanceLow < impedanceHigh;
        if (labelsSwapped) {
            float swap = impedanceHigh;
            impedanceHigh = impedanceLow;
            impedanceLow = swap;
        }

        float rawHighAfterSwap = impedanceHigh;
        boolean unreliableContact = Math.abs(impedanceLow - impedanceHigh) / impedanceHigh < 0.01f;

        float correctedHigh = impedanceHigh * FOOT_TO_FOOT_CORRECTION;
        float correctedLow = impedanceLow * FOOT_TO_FOOT_CORRECTION;
        float male = inputs.sexMale ? 1f : 0f;

        float totalBodyWaterRaw = inputs.sexMale
                ? 1.20f + 0.45f * (height * height / correctedHigh) + 0.18f * weight
                : 3.75f + 0.45f * (height * height / correctedHigh) + 0.11f * weight;
        boolean totalBodyWaterOk = inRange(totalBodyWaterRaw, 0.30f * weight, 0.75f * weight);
        Float totalBodyWater = totalBodyWaterOk ? totalBodyWaterRaw : null;

        float kEcw = inputs.sexMale ? K_ECW_M : K_ECW_F;
        float ecwRaw = kEcw * (float) Math.pow(
                ((height * height * Math.sqrt(weight)) / correctedLow),
                2.0 / 3.0);

        Float ecwTbwRatio = totalBodyWater != null && totalBodyWater > 0f
                ? ecwRaw / totalBodyWater
                : null;
        boolean waterRatioOk = ecwTbwRatio != null && inRange(ecwTbwRatio, 0.30f, 0.55f);
        Float extracellularWater = totalBodyWater != null && waterRatioOk ? ecwRaw : null;
        Float intracellularWater = totalBodyWater != null && extracellularWater != null
                ? totalBodyWater - extracellularWater
                : null;

        Float fatFreeMassRaw = totalBodyWater != null ? totalBodyWater / 0.732f : null;
        boolean fatFreeMassOk = fatFreeMassRaw != null
                && inRange(fatFreeMassRaw / weight, 0.30f, 0.97f);
        Float fatFreeMass = fatFreeMassOk ? fatFreeMassRaw : null;

        Float bodyFat = fatFreeMass != null ? weight - fatFreeMass : null;
        Float bodyFatPercentRaw = bodyFat != null ? (bodyFat / weight) * 100f : null;
        float minBodyFat = inputs.sexMale ? 3f : 8f;
        float maxBodyFat = inputs.sexMale ? 60f : 70f;
        boolean bodyFatOk = bodyFatPercentRaw != null
                && inRange(bodyFatPercentRaw, minBodyFat, maxBodyFat);
        Float bodyFatPercent = bodyFatOk ? bodyFatPercentRaw : null;
        Float bodyFatKg = bodyFatPercent != null ? bodyFat : null;

        float skeletalMuscleRaw = 0.401f * (height * height / correctedHigh)
                + 3.825f * male
                - 0.071f * inputs.age
                + 5.102f;
        float skeletalMuscle = clamp(skeletalMuscleRaw, 8f, 75f);

        // openScale default: MI_LEGACY bone formula.
        float bone = clamp(empiricalBone(height, weight, inputs.age, rawHighAfterSwap, inputs.sexMale), 1f, 6f);
        float visceralFat = clamp(empiricalVisceralFat(height, weight, inputs.age, inputs.sexMale), 1f, 30f);

        float bmrRaw = fatFreeMass != null
                ? 370f + 21.6f * fatFreeMass
                : 10f * weight + 6.25f * height - 5f * inputs.age + (inputs.sexMale ? 5f : -161f);
        float basalMetabolicRate = clamp(bmrRaw, 800f, 4000f);

        Float bodyCellMass = intracellularWater != null
                ? clamp(intracellularWater / 0.70f, 10f, 60f)
                : null;
        Float proteinKg = fatFreeMass != null
                ? Math.max(0f, 0.20f * fatFreeMass - bone)
                : null;
        Float proteinPercent = proteinKg != null ? (proteinKg / weight) * 100f : null;
        Float softLeanMass = fatFreeMass != null ? Math.max(0f, fatFreeMass - bone) : null;

        Reliability reliability;
        if (unreliableContact) reliability = Reliability.UNRELIABLE;
        else if (!totalBodyWaterOk || !fatFreeMassOk || !bodyFatOk) reliability = Reliability.APPROXIMATE;
        else reliability = Reliability.OK;

        boolean suppressCompartments = reliability == Reliability.UNRELIABLE;
        return new Result(
                weight,
                bmi,
                suppressCompartments ? null : totalBodyWater,
                suppressCompartments || totalBodyWater == null ? null : (totalBodyWater / weight) * 100f,
                suppressCompartments ? null : extracellularWater,
                suppressCompartments || extracellularWater == null ? null : (extracellularWater / weight) * 100f,
                suppressCompartments ? null : intracellularWater,
                suppressCompartments || intracellularWater == null ? null : (intracellularWater / weight) * 100f,
                suppressCompartments ? null : ecwTbwRatio,
                suppressCompartments ? null : fatFreeMass,
                suppressCompartments || fatFreeMass == null ? null : (fatFreeMass / weight) * 100f,
                suppressCompartments ? null : bodyFatKg,
                suppressCompartments ? null : bodyFatPercent,
                suppressCompartments ? null : skeletalMuscle,
                suppressCompartments ? null : (skeletalMuscle / weight) * 100f,
                bone,
                visceralFat,
                suppressCompartments ? null : basalMetabolicRate,
                suppressCompartments ? null : bodyCellMass,
                suppressCompartments ? null : proteinKg,
                suppressCompartments ? null : proteinPercent,
                suppressCompartments ? null : softLeanMass,
                null,
                reliability,
                labelsSwapped);
    }

    private static boolean isWithinValidationRange(Inputs inputs, float bmi) {
        return inputs.age >= 18 && inputs.age <= 120
                && inRange(inputs.heightCm, 100f, 230f)
                && inRange(inputs.weightKg, 20f, 250f)
                && inRange(inputs.impedanceHighRaw, 200f, 1500f)
                && inRange(inputs.impedanceLowRaw, 200f, 1500f)
                && inRange(bmi, 12f, 60f);
    }

    private static Result notAvailable(float weight, float bmi) {
        return new Result(
                weight, bmi,
                null, null,
                null, null,
                null, null,
                null,
                null, null,
                null, null,
                null, null,
                null,
                null,
                null,
                null,
                null, null,
                null,
                null,
                Reliability.NOT_AVAILABLE,
                false);
    }

    private static float empiricalBone(float height, float weight, int age,
                                       float impedanceHighRaw, boolean male) {
        float leanBodyMassCoefficient = (height * 9.058f / 100f) * (height / 100f)
                + 0.32f * weight
                + 12.226f
                - 0.0068f * impedanceHighRaw
                - 0.0542f * age;
        float base = male ? 0.18016894f : 0.245691014f;
        float raw = -(base - 0.05158f * leanBodyMassCoefficient);
        return raw > 2.2f ? raw + 0.1f : raw - 0.1f;
    }

    private static float empiricalVisceralFat(float height, float weight, int age, boolean male) {
        if (male) {
            if (height < 1.6f * weight) {
                return 305f * weight / (-(0.4f * height - 0.0826f * height * height) + 48f)
                        - 2.9f + 0.15f * age;
            }
            return -(0.143f * height - (0.765f - 0.0015f * height) * weight)
                    + 0.15f * age - 5f;
        }

        float threshold = -(13f - 0.5f * height);
        if (weight > threshold) {
            return 500f * weight / (1.45f * height + 0.1158f * height * height - 120f)
                    - 6f + 0.07f * age;
        }
        return -(0.027f * height - (0.691f - 0.0048f * height) * weight)
                + 0.07f * age - age;
    }

    private static boolean inRange(float value, float minimum, float maximum) {
        return value >= minimum && value <= maximum;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
