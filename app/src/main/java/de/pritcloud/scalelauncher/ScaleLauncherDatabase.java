package de.pritcloud.scalelauncher;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {
            MeasurementWriteJournalEntity.class,
            PendingMeasurementEntity.class,
            PendingMeasurementClaimEntity.class
        },
        version = 2,
        exportSchema = true)
public abstract class ScaleLauncherDatabase
        extends RoomDatabase {

    static final Migration MIGRATION_1_2 =
            new Migration(1, 2) {
                @Override
                public void migrate(
                        SupportSQLiteDatabase database) {
                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS `pending_measurements` ("
                                    + "`id` TEXT NOT NULL, "
                                    + "`weight_kg` REAL NOT NULL, "
                                    + "`impedance_high` REAL NOT NULL, "
                                    + "`impedance_low` REAL, "
                                    + "`scale_profile_id` INTEGER, "
                                    + "`timed_out` INTEGER NOT NULL, "
                                    + "`timestamp_ms` INTEGER NOT NULL, "
                                    + "`reason` TEXT NOT NULL, "
                                    + "`manual_rescue` INTEGER NOT NULL, "
                                    + "`candidate_profile_ids_json` TEXT NOT NULL, "
                                    + "`rejected_profile_ids_json` TEXT NOT NULL, "
                                    + "`selected_profile_id` TEXT NOT NULL, "
                                    + "`selected_owner_device_id` TEXT NOT NULL, "
                                    + "`sort_order` INTEGER NOT NULL, "
                                    + "PRIMARY KEY(`id`))");

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS `pending_measurement_claims` ("
                                    + "`measurement_id` TEXT NOT NULL, "
                                    + "`peer_device_id` TEXT NOT NULL, "
                                    + "`profile_ids_json` TEXT NOT NULL, "
                                    + "`updated_at_ms` INTEGER NOT NULL, "
                                    + "`sort_order` INTEGER NOT NULL, "
                                    + "PRIMARY KEY(`measurement_id`, `peer_device_id`))");
                }
            };

    private static volatile ScaleLauncherDatabase instance;

    public abstract MeasurementWriteJournalDao
            measurementWriteJournalDao();

    public abstract PendingMeasurementDao
            pendingMeasurementDao();

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
                                .addMigrations(
                                        MIGRATION_1_2)
                                .build();

                instance = current;
            }
        }

        return current;
    }
}
