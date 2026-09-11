package de.pritcloud.scalelauncher;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface RemotePendingMeasurementDao {
    @Query("SELECT * FROM remote_pending_measurements ORDER BY sort_order")
    List<RemotePendingMeasurementEntity> loadAll();

    @Query(
            "SELECT * FROM remote_pending_measurements "
                    + "WHERE measurement_id = :measurementId LIMIT 1")
    RemotePendingMeasurementEntity find(String measurementId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(RemotePendingMeasurementEntity entity);

    @Update
    int update(RemotePendingMeasurementEntity entity);

    @Query(
            "DELETE FROM remote_pending_measurements "
                    + "WHERE measurement_id = :measurementId")
    int delete(String measurementId);

    @Query(
            "DELETE FROM remote_pending_measurements "
                    + "WHERE collector_device_id = :collectorDeviceId")
    int deleteCollector(String collectorDeviceId);

    @Query(
            "SELECT COALESCE(MAX(sort_order), -1) "
                    + "FROM remote_pending_measurements")
    long maxSortOrder();
}
