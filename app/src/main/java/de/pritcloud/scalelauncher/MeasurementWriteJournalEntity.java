package de.pritcloud.scalelauncher;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "measurement_write_journal")
public final class MeasurementWriteJournalEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "measurement_id")
    public final String measurementId;

    @NonNull
    @ColumnInfo(name = "authority")
    public final String authority;

    @ColumnInfo(name = "user_id")
    public final long userId;

    @ColumnInfo(name = "timestamp_ms")
    public final long timestampMs;

    @NonNull
    @ColumnInfo(name = "status")
    public final String status;

    @ColumnInfo(name = "updated_at_ms")
    public final long updatedAtMs;

    public MeasurementWriteJournalEntity(
            @NonNull String measurementId,
            @NonNull String authority,
            long userId,
            long timestampMs,
            @NonNull String status,
            long updatedAtMs) {
        this.measurementId = measurementId;
        this.authority = authority;
        this.userId = userId;
        this.timestampMs = timestampMs;
        this.status = status;
        this.updatedAtMs = updatedAtMs;
    }
}
