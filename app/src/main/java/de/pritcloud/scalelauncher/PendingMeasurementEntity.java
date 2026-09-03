package de.pritcloud.scalelauncher;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "pending_measurements")
public final class PendingMeasurementEntity {
    @PrimaryKey
    @NonNull
    public final String id;

    @ColumnInfo(name = "weight_kg")
    public final float weightKg;

    @ColumnInfo(name = "impedance_high")
    public final float impedanceHigh;

    @ColumnInfo(name = "impedance_low")
    public final Float impedanceLow;

    @ColumnInfo(name = "scale_profile_id")
    public final Integer scaleProfileId;

    @ColumnInfo(name = "timed_out")
    public final boolean timedOut;

    @ColumnInfo(name = "timestamp_ms")
    public final long timestampMs;

    @NonNull
    public final String reason;

    @ColumnInfo(name = "manual_rescue")
    public final boolean manualRescue;

    @NonNull
    @ColumnInfo(name = "candidate_profile_ids_json")
    public final String candidateProfileIdsJson;

    @NonNull
    @ColumnInfo(name = "rejected_profile_ids_json")
    public final String rejectedProfileIdsJson;

    @NonNull
    @ColumnInfo(name = "selected_profile_id")
    public final String selectedProfileId;

    @NonNull
    @ColumnInfo(name = "selected_owner_device_id")
    public final String selectedOwnerDeviceId;

    @ColumnInfo(name = "sort_order")
    public final long sortOrder;

    public PendingMeasurementEntity(
            @NonNull String id,
            float weightKg,
            float impedanceHigh,
            Float impedanceLow,
            Integer scaleProfileId,
            boolean timedOut,
            long timestampMs,
            @NonNull String reason,
            boolean manualRescue,
            @NonNull String candidateProfileIdsJson,
            @NonNull String rejectedProfileIdsJson,
            @NonNull String selectedProfileId,
            @NonNull String selectedOwnerDeviceId,
            long sortOrder) {
        this.id = id;
        this.weightKg = weightKg;
        this.impedanceHigh = impedanceHigh;
        this.impedanceLow = impedanceLow;
        this.scaleProfileId = scaleProfileId;
        this.timedOut = timedOut;
        this.timestampMs = timestampMs;
        this.reason = reason;
        this.manualRescue = manualRescue;
        this.candidateProfileIdsJson = candidateProfileIdsJson;
        this.rejectedProfileIdsJson = rejectedProfileIdsJson;
        this.selectedProfileId = selectedProfileId;
        this.selectedOwnerDeviceId = selectedOwnerDeviceId;
        this.sortOrder = sortOrder;
    }
}
