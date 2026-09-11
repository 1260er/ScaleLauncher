package de.pritcloud.scalelauncher;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "remote_pending_measurements")
public final class RemotePendingMeasurementEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "measurement_id")
    public final String measurementId;

    @NonNull
    @ColumnInfo(name = "collector_device_id")
    public final String collectorDeviceId;

    @NonNull
    @ColumnInfo(name = "scale_mac")
    public final String scaleMac;

    @ColumnInfo(name = "timestamp_ms")
    public final long timestampMs;

    @ColumnInfo(name = "weight_kg")
    public final float weightKg;

    @ColumnInfo(name = "impedance_high")
    public final float impedanceHigh;

    @ColumnInfo(name = "impedance_low")
    public final float impedanceLow;

    @ColumnInfo(name = "scale_profile_id")
    public final Integer scaleProfileId;

    @NonNull
    @ColumnInfo(name = "candidate_profile_ids_json")
    public final String candidateProfileIdsJson;

    @ColumnInfo(name = "received_at_ms")
    public final long receivedAtMs;

    @ColumnInfo(name = "sort_order")
    public final long sortOrder;

    public RemotePendingMeasurementEntity(
            @NonNull String measurementId,
            @NonNull String collectorDeviceId,
            @NonNull String scaleMac,
            long timestampMs,
            float weightKg,
            float impedanceHigh,
            float impedanceLow,
            Integer scaleProfileId,
            @NonNull String candidateProfileIdsJson,
            long receivedAtMs,
            long sortOrder) {
        this.measurementId = measurementId;
        this.collectorDeviceId = collectorDeviceId;
        this.scaleMac = scaleMac;
        this.timestampMs = timestampMs;
        this.weightKg = weightKg;
        this.impedanceHigh = impedanceHigh;
        this.impedanceLow = impedanceLow;
        this.scaleProfileId = scaleProfileId;
        this.candidateProfileIdsJson = candidateProfileIdsJson;
        this.receivedAtMs = receivedAtMs;
        this.sortOrder = sortOrder;
    }
}
