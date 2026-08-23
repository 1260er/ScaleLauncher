package de.pritcloud.scalelauncher;

/** Complete raw S400 measurement received from the authenticated GATT channel. */
final class S400FinalMeasurement {
    final float weightKg;
    final float impedanceHigh;
    final Float impedanceLow;
    final long timestampMs;

    S400FinalMeasurement(float weightKg,
                         float impedanceHigh,
                         Float impedanceLow,
                         long timestampMs) {
        this.weightKg = weightKg;
        this.impedanceHigh = impedanceHigh;
        this.impedanceLow = impedanceLow;
        this.timestampMs = timestampMs;
    }

    boolean isComplete() {
        return Float.isFinite(weightKg)
                && weightKg > 0f
                && Float.isFinite(impedanceHigh)
                && impedanceHigh > 0f
                && impedanceLow != null
                && Float.isFinite(impedanceLow)
                && impedanceLow > 0f;
    }
}
