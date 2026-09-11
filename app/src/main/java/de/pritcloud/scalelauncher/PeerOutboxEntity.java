package de.pritcloud.scalelauncher;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;

@Entity(
        tableName = "peer_outbox",
        primaryKeys = {
            "peer_device_id",
            "message_id"
        })
public final class PeerOutboxEntity {
    @NonNull
    @ColumnInfo(name = "peer_device_id")
    public final String peerDeviceId;

    @NonNull
    @ColumnInfo(name = "message_id")
    public final String messageId;

    @NonNull
    @ColumnInfo(name = "kind")
    public final String kind;

    @NonNull
    @ColumnInfo(name = "dedup_key")
    public final String dedupKey;

    @NonNull
    @ColumnInfo(name = "payload")
    public final String payload;

    @ColumnInfo(name = "created_at_ms")
    public final long createdAtMs;

    @ColumnInfo(name = "sort_order")
    public final long sortOrder;

    public PeerOutboxEntity(
            @NonNull String peerDeviceId,
            @NonNull String messageId,
            @NonNull String kind,
            @NonNull String dedupKey,
            @NonNull String payload,
            long createdAtMs,
            long sortOrder) {
        this.peerDeviceId = peerDeviceId;
        this.messageId = messageId;
        this.kind = kind;
        this.dedupKey = dedupKey;
        this.payload = payload;
        this.createdAtMs = createdAtMs;
        this.sortOrder = sortOrder;
    }
}
