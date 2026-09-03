package de.pritcloud.scalelauncher;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {
            MeasurementWriteJournalEntity.class
        },
        version = 1,
        exportSchema = true)
public abstract class ScaleLauncherDatabase
        extends RoomDatabase {

    private static volatile ScaleLauncherDatabase instance;

    public abstract MeasurementWriteJournalDao
            measurementWriteJournalDao();

    static ScaleLauncherDatabase get(
            Context context) {
        ScaleLauncherDatabase current = instance;

        if (current != null) {
            return current;
        }

        synchronized (ScaleLauncherDatabase.class) {
            current = instance;

            if (current == null) {
                current =
                        Room.databaseBuilder(
                                        context.getApplicationContext(),
                                        ScaleLauncherDatabase.class,
                                        "scalelauncher.db")
                                .build();

                instance = current;
            }
        }

        return current;
    }
}
