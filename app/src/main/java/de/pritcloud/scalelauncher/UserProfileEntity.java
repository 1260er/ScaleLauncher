package de.pritcloud.scalelauncher;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "user_profiles")
final class UserProfileEntity {
    @PrimaryKey
    @ColumnInfo(name = "user_id")
    final long userId;

    @NonNull
    @ColumnInfo(name = "name")
    final String name;

    @ColumnInfo(name = "enabled")
    final boolean enabled;

    @NonNull
    @ColumnInfo(name = "birth_date_iso")
    final String birthDateIso;

    @ColumnInfo(name = "height_cm")
    final float heightCm;

    @ColumnInfo(name = "male")
    final boolean male;

    @ColumnInfo(name = "reference_weight_kg")
    final float referenceWeightKg;

    @ColumnInfo(name = "tolerance_kg")
    final float toleranceKg;

    @NonNull
    @ColumnInfo(name = "owner_device_id")
    final String ownerDeviceId;

    @NonNull
    @ColumnInfo(name = "household_profile_id")
    final String householdProfileId;

    @ColumnInfo(name = "household_updated_at_ms")
    final long householdUpdatedAtMs;

    @ColumnInfo(name = "sort_order")
    final long sortOrder;

    UserProfileEntity(
            long userId,
            @NonNull String name,
            boolean enabled,
            @NonNull String birthDateIso,
            float heightCm,
            boolean male,
            float referenceWeightKg,
            float toleranceKg,
            @NonNull String ownerDeviceId,
            @NonNull String householdProfileId,
            long householdUpdatedAtMs,
            long sortOrder) {
        this.userId = userId;
        this.name = name;
        this.enabled = enabled;
        this.birthDateIso = birthDateIso;
        this.heightCm = heightCm;
        this.male = male;
        this.referenceWeightKg = referenceWeightKg;
        this.toleranceKg = toleranceKg;
        this.ownerDeviceId = ownerDeviceId;
        this.householdProfileId = householdProfileId;
        this.householdUpdatedAtMs = householdUpdatedAtMs;
        this.sortOrder = sortOrder;
    }
}
