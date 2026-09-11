package de.pritcloud.scalelauncher;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "openscale_pending")
final class OpenScalePendingEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "measurement_id")
    final String measurementId;

    @ColumnInfo(name = "user_id")
    final long userId;

    @NonNull
    @ColumnInfo(name = "household_profile_id")
    final String householdProfileId;

    @ColumnInfo(name = "weight_kg")
    final float weightKg;

    @ColumnInfo(name = "impedance_high")
    final float impedanceHigh;

    @ColumnInfo(name = "impedance_low")
    final Float impedanceLow;

    @ColumnInfo(name = "scale_profile_id")
    final Integer scaleProfileId;

    @ColumnInfo(name = "timestamp_ms")
    final long timestampMs;

    @ColumnInfo(name = "queued_at_ms")
    final long queuedAtMs;

    OpenScalePendingEntity(
            @NonNull String measurementId,
            long userId,
            @NonNull String householdProfileId,
            float weightKg,
            float impedanceHigh,
            Float impedanceLow,
            Integer scaleProfileId,
            long timestampMs,
            long queuedAtMs) {
        this.measurementId = measurementId;
        this.userId = userId;
        this.householdProfileId = householdProfileId;
        this.weightKg = weightKg;
        this.impedanceHigh = impedanceHigh;
        this.impedanceLow = impedanceLow;
        this.scaleProfileId = scaleProfileId;
        this.timestampMs = timestampMs;
        this.queuedAtMs = queuedAtMs;
    }
}
