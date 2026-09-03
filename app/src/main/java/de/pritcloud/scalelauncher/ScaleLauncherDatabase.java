package de.pritcloud.scalelauncher;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(
        entities = {
            MeasurementWriteJournalEntity.class
        },
        version = 1,
        exportSchema = true)
public abstract class ScaleLauncherDatabase
        extends RoomDatabase {

    public abstract MeasurementWriteJournalDao
            measurementWriteJournalDao();
}
