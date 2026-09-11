package de.pritcloud.scalelauncher;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "trusted_peers")
final class PeerTrustEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "device_id")
    final String deviceId;

    @NonNull
    @ColumnInfo(name = "label")
    final String label;

    @NonNull
    @ColumnInfo(name = "encrypted_secret")
    final String encryptedSecret;

    @ColumnInfo(name = "sort_order")
    final long sortOrder;

    PeerTrustEntity(
            @NonNull String deviceId,
            @NonNull String label,
            @NonNull String encryptedSecret,
            long sortOrder) {
        this.deviceId = deviceId;
        this.label = label;
        this.encryptedSecret = encryptedSecret;
        this.sortOrder = sortOrder;
    }
}
