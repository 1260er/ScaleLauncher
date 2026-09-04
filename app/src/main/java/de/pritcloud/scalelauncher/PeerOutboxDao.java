package de.pritcloud.scalelauncher;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PeerOutboxDao {
    @Query("SELECT * FROM peer_outbox ORDER BY sort_order")
    List<PeerOutboxEntity> loadAll();

    @Query(
            "SELECT * FROM peer_outbox "
                    + "WHERE peer_device_id = :peerDeviceId "
                    + "AND message_id = :messageId LIMIT 1")
    PeerOutboxEntity find(
            String peerDeviceId,
            String messageId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(PeerOutboxEntity entity);

    @Query(
            "DELETE FROM peer_outbox "
                    + "WHERE peer_device_id = :peerDeviceId "
                    + "AND message_id = :messageId")
    int delete(
            String peerDeviceId,
            String messageId);

    @Query(
            "DELETE FROM peer_outbox "
                    + "WHERE peer_device_id = :peerDeviceId")
    int deletePeer(String peerDeviceId);

    @Query(
            "DELETE FROM peer_outbox "
                    + "WHERE peer_device_id = :peerDeviceId "
                    + "AND kind = :kind "
                    + "AND dedup_key = :dedupKey")
    int deleteCoalesced(
            String peerDeviceId,
            String kind,
            String dedupKey);

    @Query("SELECT COUNT(*) FROM peer_outbox")
    int count();

    @Query(
            "SELECT COALESCE(MAX(sort_order), -1) "
                    + "FROM peer_outbox")
    long maxSortOrder();
}
