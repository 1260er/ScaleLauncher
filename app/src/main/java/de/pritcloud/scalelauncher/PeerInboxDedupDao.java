package de.pritcloud.scalelauncher;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PeerInboxDedupDao {
    @Query(
            "SELECT * FROM peer_inbox_dedup "
                    + "WHERE sender_device_id = :senderDeviceId "
                    + "AND message_id = :messageId "
                    + "LIMIT 1")
    PeerInboxDedupEntity find(
            String senderDeviceId,
            String messageId);

    @Query(
            "SELECT EXISTS("
                    + "SELECT 1 FROM peer_inbox_dedup "
                    + "WHERE sender_device_id = :senderDeviceId "
                    + "AND message_id = :messageId)")
    boolean contains(
            String senderDeviceId,
            String messageId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long upsert(
            PeerInboxDedupEntity entity);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(
            PeerInboxDedupEntity entity);

    @Query(
            "SELECT DISTINCT sender_device_id "
                    + "FROM peer_inbox_dedup "
                    + "ORDER BY sender_device_id")
    List<String> senderDeviceIds();

    @Query(
            "DELETE FROM peer_inbox_dedup "
                    + "WHERE sender_device_id = :peerDeviceId")
    int deletePeer(
            String peerDeviceId);

    @Query("SELECT COUNT(*) FROM peer_inbox_dedup")
    int count();
}
