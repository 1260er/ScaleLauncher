package de.pritcloud.scalelauncher;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface UserProfileDao {
    @Query(
            "SELECT * FROM user_profiles "
                    + "ORDER BY sort_order")
    List<UserProfileEntity> loadAll();

    @Query(
            "SELECT * FROM user_profiles "
                    + "WHERE user_id = :userId "
                    + "LIMIT 1")
    UserProfileEntity find(
            long userId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(
            UserProfileEntity entity);

    @Query("DELETE FROM user_profiles")
    int deleteAll();

    @Query("SELECT COUNT(*) FROM user_profiles")
    int count();

    @Query("SELECT MAX(sort_order) FROM user_profiles")
    Long maxSortOrder();
}
