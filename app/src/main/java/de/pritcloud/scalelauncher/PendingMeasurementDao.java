package de.pritcloud.scalelauncher;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface PendingMeasurementDao {
    @Query("SELECT * FROM pending_measurements ORDER BY sort_order")
    List<PendingMeasurementEntity> loadPending();

    @Query("SELECT * FROM pending_measurements WHERE id = :id LIMIT 1")
    PendingMeasurementEntity findPending(String id);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertPending(PendingMeasurementEntity entity);

    @Update
    int updatePending(PendingMeasurementEntity entity);

    @Query("DELETE FROM pending_measurements WHERE id = :id")
    int deletePending(String id);

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM pending_measurements")
    long maxPendingSortOrder();

    @Query(
            "SELECT * FROM pending_measurement_claims "
                    + "WHERE measurement_id = :measurementId "
                    + "ORDER BY sort_order")
    List<PendingMeasurementClaimEntity> loadClaims(String measurementId);

    @Query(
            "SELECT * FROM pending_measurement_claims "
                    + "WHERE measurement_id = :measurementId "
                    + "AND peer_device_id = :peerDeviceId LIMIT 1")
    PendingMeasurementClaimEntity findClaim(
            String measurementId,
            String peerDeviceId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long upsertClaim(PendingMeasurementClaimEntity entity);

    @Query(
            "DELETE FROM pending_measurement_claims "
                    + "WHERE measurement_id = :measurementId")
    int deleteClaimsForMeasurement(String measurementId);

    @Query(
            "DELETE FROM pending_measurement_claims "
                    + "WHERE peer_device_id = :peerDeviceId")
    int deleteClaimsForPeer(String peerDeviceId);

    @Query(
            "SELECT COALESCE(MAX(sort_order), -1) "
                    + "FROM pending_measurement_claims")
    long maxClaimSortOrder();
}
