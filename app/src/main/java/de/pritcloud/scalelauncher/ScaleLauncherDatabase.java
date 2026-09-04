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
            PendingMeasurementClaimEntity.class,
            RemotePendingMeasurementEntity.class,
            PeerOutboxEntity.class,
            PeerInboxDedupEntity.class
        },
        version = 5,
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

    static final Migration MIGRATION_2_3 =
            new Migration(2, 3) {
                @Override
                public void migrate(
                        SupportSQLiteDatabase database) {
                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS `remote_pending_measurements` ("
                                    + "`measurement_id` TEXT NOT NULL, "
                                    + "`collector_device_id` TEXT NOT NULL, "
                                    + "`scale_mac` TEXT NOT NULL, "
                                    + "`timestamp_ms` INTEGER NOT NULL, "
                                    + "`weight_kg` REAL NOT NULL, "
                                    + "`impedance_high` REAL NOT NULL, "
                                    + "`impedance_low` REAL NOT NULL, "
                                    + "`scale_profile_id` INTEGER, "
                                    + "`candidate_profile_ids_json` TEXT NOT NULL, "
                                    + "`received_at_ms` INTEGER NOT NULL, "
                                    + "`sort_order` INTEGER NOT NULL, "
                                    + "PRIMARY KEY(`measurement_id`))");
                }
            };

    static final Migration MIGRATION_3_4 =
            new Migration(3, 4) {
                @Override
                public void migrate(
                        SupportSQLiteDatabase database) {
                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS `peer_outbox` ("
                                    + "`peer_device_id` TEXT NOT NULL, "
                                    + "`message_id` TEXT NOT NULL, "
                                    + "`kind` TEXT NOT NULL, "
                                    + "`dedup_key` TEXT NOT NULL, "
                                    + "`payload` TEXT NOT NULL, "
                                    + "`created_at_ms` INTEGER NOT NULL, "
                                    + "`sort_order` INTEGER NOT NULL, "
                                    + "PRIMARY KEY(`peer_device_id`, `message_id`))");
                }
            };

    static final Migration MIGRATION_4_5 =
            new Migration(4, 5) {
                @Override
                public void migrate(
                        SupportSQLiteDatabase database) {
                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS `peer_inbox_dedup` ("
                                    + "`sender_device_id` TEXT NOT NULL, "
                                    + "`message_id` TEXT NOT NULL, "
                                    + "`seen_at_ms` INTEGER NOT NULL, "
                                    + "PRIMARY KEY(`sender_device_id`, `message_id`))");
                }
            };

    private static volatile ScaleLauncherDatabase instance;

    public abstract MeasurementWriteJournalDao
            measurementWriteJournalDao();

    public abstract PendingMeasurementDao
            pendingMeasurementDao();

    public abstract RemotePendingMeasurementDao
            remotePendingMeasurementDao();

    public abstract PeerOutboxDao
            peerOutboxDao();

    public abstract PeerInboxDedupDao
            peerInboxDedupDao();

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
                                        MIGRATION_1_2,
                                        MIGRATION_2_3,
                                        MIGRATION_3_4,
                                        MIGRATION_4_5)
                                .build();

                instance = current;
            }
        }

        return current;
    }
}
