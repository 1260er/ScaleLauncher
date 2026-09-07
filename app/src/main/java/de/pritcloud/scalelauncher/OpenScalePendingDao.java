package de.pritcloud.scalelauncher;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface OpenScalePendingDao {
    @Query(
            "SELECT * FROM openscale_pending "
                    + "ORDER BY timestamp_ms, queued_at_ms")
    List<OpenScalePendingEntity> loadAll();

    @Query(
            "SELECT * FROM openscale_pending "
                    + "WHERE household_profile_id = :householdProfileId "
                    + "ORDER BY timestamp_ms, queued_at_ms")
    List<OpenScalePendingEntity> loadForProfile(
            String householdProfileId);

    @Query(
            "SELECT * FROM openscale_pending "
                    + "WHERE measurement_id = :measurementId "
                    + "LIMIT 1")
    OpenScalePendingEntity find(
            String measurementId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(
            OpenScalePendingEntity entity);

    @Query(
            "DELETE FROM openscale_pending "
                    + "WHERE measurement_id = :measurementId")
    int delete(
            String measurementId);

    @Query(
            "SELECT COUNT(*) FROM openscale_pending "
                    + "WHERE household_profile_id = :householdProfileId")
    int countForProfile(
            String householdProfileId);
}
