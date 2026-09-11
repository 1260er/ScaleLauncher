package de.pritcloud.scalelauncher;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface HouseholdProfileDao {
    @Query(
            "SELECT * FROM household_profiles "
                    + "ORDER BY sort_order")
    List<HouseholdProfileEntity> loadAll();

    @Query(
            "SELECT * FROM household_profiles "
                    + "WHERE profile_id = :profileId "
                    + "LIMIT 1")
    HouseholdProfileEntity find(
            String profileId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(
            HouseholdProfileEntity entity);

    @Update
    int update(
            HouseholdProfileEntity entity);

    @Query(
            "DELETE FROM household_profiles "
                    + "WHERE profile_id = :profileId")
    int delete(
            String profileId);

    @Query(
            "DELETE FROM household_profiles "
                    + "WHERE owner_device_id = :ownerDeviceId")
    int deleteOwner(
            String ownerDeviceId);

    @Query("SELECT COUNT(*) FROM household_profiles")
    int count();

    @Query("SELECT MAX(sort_order) FROM household_profiles")
    Long maxSortOrder();
}
