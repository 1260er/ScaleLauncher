package de.pritcloud.scalelauncher;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface PeerTrustDao {
    @Query(
            "SELECT * FROM trusted_peers "
                    + "ORDER BY sort_order")
    List<PeerTrustEntity> loadAll();

    @Query(
            "SELECT * FROM trusted_peers "
                    + "WHERE device_id = :deviceId "
                    + "LIMIT 1")
    PeerTrustEntity find(
            String deviceId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(
            PeerTrustEntity entity);

    @Update
    int update(
            PeerTrustEntity entity);

    @Query(
            "DELETE FROM trusted_peers "
                    + "WHERE device_id = :deviceId")
    int delete(
            String deviceId);

    @Query("DELETE FROM trusted_peers")
    int deleteAll();

    @Query("SELECT COUNT(*) FROM trusted_peers")
    int count();

    @Query("SELECT MAX(sort_order) FROM trusted_peers")
    Long maxSortOrder();
}
