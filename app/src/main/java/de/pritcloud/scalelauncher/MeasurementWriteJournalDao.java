package de.pritcloud.scalelauncher;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

@Dao
public interface MeasurementWriteJournalDao {
    @Query(
            "SELECT * FROM measurement_write_journal "
                    + "WHERE measurement_id = :measurementId LIMIT 1")
    MeasurementWriteJournalEntity findByMeasurementId(
            String measurementId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(
            MeasurementWriteJournalEntity entry);

    @Update
    int update(
            MeasurementWriteJournalEntity entry);
}
