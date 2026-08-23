package de.pritcloud.scalelauncher;

import java.util.UUID;

/** Complete raw S400 measurement received from the authenticated GATT channel. */
final class S400FinalMeasurement {
    final String measurementId;
    final float weightKg;
    final float impedanceHigh;
    final Float impedanceLow;
    final long timestampMs;
    final Integer scaleProfileId;

    S400FinalMeasurement(float weightKg,
                         float impedanceHigh,
                         Float impedanceLow,
                         long timestampMs) {
        this(
                UUID.randomUUID().toString(),
                weightKg,
                impedanceHigh,
                impedanceLow,
                timestampMs,
                null);
    }

    S400FinalMeasurement(float weightKg,
                         float impedanceHigh,
                         Float impedanceLow,
                         long timestampMs,
                         Integer scaleProfileId) {
        this(
                UUID.randomUUID().toString(),
                weightKg,
                impedanceHigh,
                impedanceLow,
                timestampMs,
                scaleProfileId);
    }

    S400FinalMeasurement(String measurementId,
                         float weightKg,
                         float impedanceHigh,
                         Float impedanceLow,
                         long timestampMs,
                         Integer scaleProfileId) {
        this.measurementId =
                measurementId == null || measurementId.isBlank()
                        ? UUID.randomUUID().toString()
                        : measurementId;
        this.weightKg = weightKg;
        this.impedanceHigh = impedanceHigh;
        this.impedanceLow = impedanceLow;
        this.timestampMs = timestampMs;
        this.scaleProfileId = scaleProfileId;
    }

    boolean isComplete() {
        return !measurementId.isBlank()
                && Float.isFinite(weightKg)
                && weightKg > 0f
                && Float.isFinite(impedanceHigh)
                && impedanceHigh > 0f
                && impedanceLow != null
                && Float.isFinite(impedanceLow)
                && impedanceLow > 0f
                && timestampMs > 0L;
    }
}
