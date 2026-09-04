package de.pritcloud.scalelauncher;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;

@Entity(
        tableName = "peer_inbox_dedup",
        primaryKeys = {
            "sender_device_id",
            "message_id"
        })
public final class PeerInboxDedupEntity {
    @NonNull
    @ColumnInfo(name = "sender_device_id")
    public final String senderDeviceId;

    @NonNull
    @ColumnInfo(name = "message_id")
    public final String messageId;

    @ColumnInfo(name = "seen_at_ms")
    public final long seenAtMs;

    public PeerInboxDedupEntity(
            @NonNull String senderDeviceId,
            @NonNull String messageId,
            long seenAtMs) {
        this.senderDeviceId = senderDeviceId;
        this.messageId = messageId;
        this.seenAtMs = seenAtMs;
    }
}
