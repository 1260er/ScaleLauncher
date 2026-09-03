package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class MeasurementWriteJournalStore {
    private static final String PREFS =
            "measurement_write_journal_v1";

    private static final String KEY =
            "entries";

    private static final String MIGRATION_KEY =
            "room_migration_complete_v1";

    private static final ExecutorService DB_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread =
                                new Thread(
                                        runnable,
                                        "ScaleLauncherJournal");

                        thread.setDaemon(true);
                        return thread;
                    });

    private static volatile boolean migrationVerified;

    enum Status {
        MISSING,
        PREPARED,
        STORED,
        CONFLICT
    }

    private interface DaoOperation<T> {
        T run(
                MeasurementWriteJournalDao dao);
    }

    private static final class ParseResult {
        final boolean valid;
        final List<MeasurementWriteJournalEntity> entries;

        ParseResult(
                boolean valid,
                List<MeasurementWriteJournalEntity> entries) {
            this.valid = valid;
            this.entries = entries;
        }
    }

    private MeasurementWriteJournalStore() {}

    static Status status(
            Context context,
            String measurementId,
            String authority,
            long userId,
            long timestampMs) {
        return runRoom(
                context,
                dao ->
                        status(
                                dao,
                                measurementId,
                                authority,
                                userId,
                                timestampMs),
                Status.CONFLICT);
    }

    static boolean prepare(
            Context context,
            String measurementId,
            String authority,
            long userId,
            long timestampMs) {
        return runRoom(
                context,
                dao ->
                        prepare(
                                dao,
                                measurementId,
                                authority,
                                userId,
                                timestampMs),
                false);
    }

    static boolean markStored(
            Context context,
            String measurementId,
            String authority,
            long userId,
            long timestampMs) {
        return runRoom(
                context,
                dao ->
                        markStored(
                                dao,
                                measurementId,
                                authority,
                                userId,
                                timestampMs),
                false);
    }

    static Status status(
            MeasurementWriteJournalDao dao,
            String measurementId,
            String authority,
            long userId,
            long timestampMs) {
        if (measurementId == null
                || measurementId.isBlank()) {
            return Status.MISSING;
        }

        MeasurementWriteJournalEntity entry =
                dao.findByMeasurementId(
                        measurementId);

        if (entry == null) {
            return Status.MISSING;
        }

        if (!matches(
                entry,
                authority,
                userId,
                timestampMs)) {
            return Status.CONFLICT;
        }

        Status storedStatus =
                storedStatus(
                        entry.status);

        return storedStatus == null
                ? Status.CONFLICT
                : storedStatus;
    }

    static boolean prepare(
            MeasurementWriteJournalDao dao,
            String measurementId,
            String authority,
            long userId,
            long timestampMs) {
        return writeState(
                dao,
                measurementId,
                authority,
                userId,
                timestampMs,
                Status.PREPARED);
    }

    static boolean markStored(
            MeasurementWriteJournalDao dao,
            String measurementId,
            String authority,
            long userId,
            long timestampMs) {
        return writeState(
                dao,
                measurementId,
                authority,
                userId,
                timestampMs,
                Status.STORED);
    }

    static boolean migrateLegacyForTest(
            SharedPreferences preferences,
            MeasurementWriteJournalDao dao) {
        ParseResult parsed =
                parseLegacy(
                        preferences);

        if (!parsed.valid
                || !importLegacyEntries(
                        dao,
                        parsed.entries)) {
            return false;
        }

        return preferences
                .edit()
                .putBoolean(
                        MIGRATION_KEY,
                        true)
                .commit();
    }

    static boolean isLegacyMigrationMarked(
            SharedPreferences preferences) {
        return preferences.getBoolean(
                MIGRATION_KEY,
                false);
    }

    private static boolean writeState(
            MeasurementWriteJournalDao dao,
            String measurementId,
            String authority,
            long userId,
            long timestampMs,
            Status newStatus) {
        if (!validIdentity(
                measurementId,
                authority,
                userId,
                timestampMs)
                || newStatus == null
                || (newStatus != Status.PREPARED
                && newStatus != Status.STORED)) {
            return false;
        }

        MeasurementWriteJournalEntity existing =
                dao.findByMeasurementId(
                        measurementId);

        if (existing == null) {
            MeasurementWriteJournalEntity entry =
                    new MeasurementWriteJournalEntity(
                            measurementId,
                            authority,
                            userId,
                            timestampMs,
                            newStatus.name(),
                            System.currentTimeMillis());

            long inserted =
                    dao.insert(
                            entry);

            if (inserted != -1L) {
                return true;
            }

            existing =
                    dao.findByMeasurementId(
                            measurementId);

            if (existing == null) {
                return false;
            }
        }

        if (!matches(
                existing,
                authority,
                userId,
                timestampMs)) {
            return false;
        }

        Status existingStatus =
                storedStatus(
                        existing.status);

        if (existingStatus == null) {
            return false;
        }

        if (existingStatus == Status.STORED
                && newStatus == Status.PREPARED) {
            return true;
        }

        MeasurementWriteJournalEntity updated =
                new MeasurementWriteJournalEntity(
                        measurementId,
                        authority,
                        userId,
                        timestampMs,
                        newStatus.name(),
                        System.currentTimeMillis());

        return dao.update(
                updated) == 1;
    }

    private static <T> T runRoom(
            Context context,
            DaoOperation<T> operation,
            T failureValue) {
        if (context == null
                || operation == null) {
            return failureValue;
        }

        Context appContext =
                context.getApplicationContext();

        try {
            return DB_EXECUTOR
                    .submit(
                            () -> {
                                ScaleLauncherDatabase database =
                                        ScaleLauncherDatabase.get(
                                                appContext);

                                if (!ensureLegacyMigrated(
                                        appContext,
                                        database)) {
                                    return failureValue;
                                }

                                return operation.run(
                                        database.measurementWriteJournalDao());
                            })
                    .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failureValue;
        } catch (ExecutionException | RuntimeException e) {
            return failureValue;
        }
    }

    private static boolean ensureLegacyMigrated(
            Context context,
            ScaleLauncherDatabase database) {
        if (migrationVerified) {
            return true;
        }

        SharedPreferences preferences =
                prefs(
                        context);

        ParseResult parsed =
                parseLegacy(
                        preferences);

        if (!parsed.valid) {
            return false;
        }

        try {
            database.runInTransaction(
                    () -> {
                        if (!importLegacyEntries(
                                database.measurementWriteJournalDao(),
                                parsed.entries)) {
                            throw new IllegalStateException(
                                    "Measurement journal migration conflict");
                        }
                    });
        } catch (RuntimeException e) {
            return false;
        }

        if (!preferences.getBoolean(
                MIGRATION_KEY,
                false)) {
            boolean markerStored =
                    preferences
                            .edit()
                            .putBoolean(
                                    MIGRATION_KEY,
                                    true)
                            .commit();

            if (!markerStored) {
                return false;
            }
        }

        migrationVerified = true;
        return true;
    }

    private static boolean importLegacyEntries(
            MeasurementWriteJournalDao dao,
            List<MeasurementWriteJournalEntity> entries) {
        for (MeasurementWriteJournalEntity legacy :
                entries) {
            MeasurementWriteJournalEntity existing =
                    dao.findByMeasurementId(
                            legacy.measurementId);

            if (existing == null) {
                long inserted =
                        dao.insert(
                                legacy);

                if (inserted != -1L) {
                    continue;
                }

                existing =
                        dao.findByMeasurementId(
                                legacy.measurementId);

                if (existing == null) {
                    return false;
                }
            }

            if (!matches(
                    existing,
                    legacy.authority,
                    legacy.userId,
                    legacy.timestampMs)) {
                return false;
            }

            Status existingStatus =
                    storedStatus(
                            existing.status);

            Status legacyStatus =
                    storedStatus(
                            legacy.status);

            if (existingStatus == null
                    || legacyStatus == null) {
                return false;
            }

            Status targetStatus =
                    existingStatus == Status.STORED
                            || legacyStatus == Status.STORED
                            ? Status.STORED
                            : Status.PREPARED;

            long targetUpdatedAt =
                    Math.max(
                            existing.updatedAtMs,
                            legacy.updatedAtMs);

            if (existingStatus == targetStatus
                    && existing.updatedAtMs == targetUpdatedAt) {
                continue;
            }

            MeasurementWriteJournalEntity merged =
                    new MeasurementWriteJournalEntity(
                            existing.measurementId,
                            existing.authority,
                            existing.userId,
                            existing.timestampMs,
                            targetStatus.name(),
                            targetUpdatedAt);

            if (dao.update(
                    merged) != 1) {
                return false;
            }
        }

        return true;
    }

    private static ParseResult parseLegacy(
            SharedPreferences preferences) {
        List<MeasurementWriteJournalEntity> empty =
                new ArrayList<>();

        String encoded =
                preferences.getString(
                        KEY,
                        "");

        if (encoded == null
                || encoded.isBlank()) {
            return new ParseResult(
                    true,
                    empty);
        }

        try {
            JSONArray array =
                    new JSONArray(
                            encoded);

            Map<String, MeasurementWriteJournalEntity> byId =
                    new LinkedHashMap<>();

            for (int index = 0;
                 index < array.length();
                 index++) {
                JSONObject object =
                        array.optJSONObject(
                                index);

                if (object == null) {
                    return new ParseResult(
                            false,
                            empty);
                }

                String measurementId =
                        object.optString(
                                "measurementId",
                                "");

                String authority =
                        object.optString(
                                "authority",
                                "");

                long userId =
                        object.optLong(
                                "userId",
                                -1L);

                long timestampMs =
                        object.optLong(
                                "timestampMs",
                                0L);

                long updatedAtMs =
                        object.optLong(
                                "updatedAtMs",
                                0L);

                Status status;

                try {
                    status =
                            Status.valueOf(
                                    object.optString(
                                            "status",
                                            ""));
                } catch (IllegalArgumentException e) {
                    return new ParseResult(
                            false,
                            empty);
                }

                if (!validIdentity(
                        measurementId,
                        authority,
                        userId,
                        timestampMs)
                        || updatedAtMs <= 0L
                        || (status != Status.PREPARED
                        && status != Status.STORED)) {
                    return new ParseResult(
                            false,
                            empty);
                }

                MeasurementWriteJournalEntity candidate =
                        new MeasurementWriteJournalEntity(
                                measurementId,
                                authority,
                                userId,
                                timestampMs,
                                status.name(),
                                updatedAtMs);

                MeasurementWriteJournalEntity previous =
                        byId.get(
                                measurementId);

                if (previous == null) {
                    byId.put(
                            measurementId,
                            candidate);
                    continue;
                }

                if (!matches(
                        previous,
                        authority,
                        userId,
                        timestampMs)) {
                    return new ParseResult(
                            false,
                            empty);
                }

                Status previousStatus =
                        storedStatus(
                                previous.status);

                Status mergedStatus =
                        previousStatus == Status.STORED
                                || status == Status.STORED
                                ? Status.STORED
                                : Status.PREPARED;

                byId.put(
                        measurementId,
                        new MeasurementWriteJournalEntity(
                                measurementId,
                                authority,
                                userId,
                                timestampMs,
                                mergedStatus.name(),
                                Math.max(
                                        previous.updatedAtMs,
                                        updatedAtMs)));
            }

            return new ParseResult(
                    true,
                    new ArrayList<>(
                            byId.values()));
        } catch (JSONException | RuntimeException e) {
            return new ParseResult(
                    false,
                    empty);
        }
    }

    private static boolean validIdentity(
            String measurementId,
            String authority,
            long userId,
            long timestampMs) {
        return measurementId != null
                && !measurementId.isBlank()
                && measurementId.length() <= 200
                && authority != null
                && !authority.isBlank()
                && userId >= 0L
                && timestampMs > 0L;
    }

    private static boolean matches(
            MeasurementWriteJournalEntity entry,
            String authority,
            long userId,
            long timestampMs) {
        return entry != null
                && entry.authority.equals(
                        authority)
                && entry.userId == userId
                && entry.timestampMs == timestampMs;
    }

    private static Status storedStatus(
            String encoded) {
        if (Status.PREPARED.name().equals(
                encoded)) {
            return Status.PREPARED;
        }

        if (Status.STORED.name().equals(
                encoded)) {
            return Status.STORED;
        }

        return null;
    }

    private static SharedPreferences prefs(
            Context context) {
        return context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE);
    }
}
