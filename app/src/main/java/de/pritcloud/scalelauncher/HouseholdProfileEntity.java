package de.pritcloud.scalelauncher;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "household_profiles")
public final class HouseholdProfileEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "profile_id")
    public final String profileId;

    @NonNull
    @ColumnInfo(name = "name")
    public final String name;

    @NonNull
    @ColumnInfo(name = "owner_device_id")
    public final String ownerDeviceId;

    @ColumnInfo(name = "reference_weight_kg")
    public final float referenceWeightKg;

    @ColumnInfo(name = "tolerance_kg")
    public final float toleranceKg;

    @ColumnInfo(name = "active")
    public final boolean active;

    @ColumnInfo(name = "updated_at_ms")
    public final long updatedAtMs;

    @ColumnInfo(name = "sort_order")
    public final long sortOrder;

    public HouseholdProfileEntity(
            @NonNull String profileId,
            @NonNull String name,
            @NonNull String ownerDeviceId,
            float referenceWeightKg,
            float toleranceKg,
            boolean active,
            long updatedAtMs,
            long sortOrder) {
        this.profileId = profileId;
        this.name = name;
        this.ownerDeviceId = ownerDeviceId;
        this.referenceWeightKg = referenceWeightKg;
        this.toleranceKg = toleranceKg;
        this.active = active;
        this.updatedAtMs = updatedAtMs;
        this.sortOrder = sortOrder;
    }
}
