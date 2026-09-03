package de.pritcloud.scalelauncher;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;

@Entity(
        tableName = "pending_measurement_claims",
        primaryKeys = {
            "measurement_id",
            "peer_device_id"
        })
public final class PendingMeasurementClaimEntity {
    @NonNull
    @ColumnInfo(name = "measurement_id")
    public final String measurementId;

    @NonNull
    @ColumnInfo(name = "peer_device_id")
    public final String peerDeviceId;

    @NonNull
    @ColumnInfo(name = "profile_ids_json")
    public final String profileIdsJson;

    @ColumnInfo(name = "updated_at_ms")
    public final long updatedAtMs;

    @ColumnInfo(name = "sort_order")
    public final long sortOrder;

    public PendingMeasurementClaimEntity(
            @NonNull String measurementId,
            @NonNull String peerDeviceId,
            @NonNull String profileIdsJson,
            long updatedAtMs,
            long sortOrder) {
        this.measurementId = measurementId;
        this.peerDeviceId = peerDeviceId;
        this.profileIdsJson = profileIdsJson;
        this.updatedAtMs = updatedAtMs;
        this.sortOrder = sortOrder;
    }
}
