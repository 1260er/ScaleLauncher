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
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class RemotePendingMeasurementRoomStore {
    private static final String PREFS =
            "remote_pending_measurements_v1";

    private static final String KEY =
            "items";

    private static final String MIGRATION_KEY =
            "remote_pending_measurements_room_migration_complete_v1";

    private static final ExecutorService DB_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread =
                                new Thread(
                                        runnable,
                                        "ScaleLauncherRemotePending");
                        thread.setDaemon(true);
                        return thread;
                    });

    private static volatile boolean migrationVerified;

    private interface DatabaseOperation<T> {
        T run(ScaleLauncherDatabase database);
    }

    private static final class LegacyData {
        final boolean valid;
        final List<RemotePendingMeasurementEntity> items;

        LegacyData(
                boolean valid,
                List<RemotePendingMeasurementEntity> items) {
            this.valid = valid;
            this.items = items;
        }
    }

    private RemotePendingMeasurementRoomStore() {
    }

    static List<RemotePendingMeasurementStore.Item> load(
            Context context) {
        return runRoom(
                context,
                database ->
                        load(
                                database.remotePendingMeasurementDao()));
    }

    static RemotePendingMeasurementStore.Item find(
            Context context,
            String measurementId) {
        return runRoom(
                context,
                database ->
                        find(
                                database.remotePendingMeasurementDao(),
                                measurementId));
    }

    static boolean upsert(
            Context context,
            PeerTrustStore.Peer collector,
            PeerMeasurementPayload payload,
            List<String> localCandidateProfileIds) {
        return runRoom(
                context,
                database ->
                        upsert(
                                database.remotePendingMeasurementDao(),
                                collector,
                                payload,
                                localCandidateProfileIds));
    }

    static boolean remove(
            Context context,
            String measurementId) {
        return runRoom(
                context,
                database ->
                        remove(
                                database.remotePendingMeasurementDao(),
                                measurementId));
    }

    static int removeCollector(
            Context context,
            String collectorDeviceId) {
        return runRoom(
                context,
                database ->
                        removeCollector(
                                database.remotePendingMeasurementDao(),
                                collectorDeviceId));
    }

    static List<RemotePendingMeasurementStore.Item> load(
            RemotePendingMeasurementDao dao) {
        List<RemotePendingMeasurementStore.Item> result =
                new ArrayList<>();

        for (RemotePendingMeasurementEntity entity :
                dao.loadAll()) {
            result.add(
                    fromEntity(
                            entity));
        }

        return result;
    }

    static RemotePendingMeasurementStore.Item find(
            RemotePendingMeasurementDao dao,
            String measurementId) {
        if (measurementId == null
                || measurementId.isBlank()) {
            return null;
        }

        RemotePendingMeasurementEntity entity =
                dao.find(
                        measurementId);

        return entity == null
                ? null
                : fromEntity(entity);
    }

    static boolean upsert(
            RemotePendingMeasurementDao dao,
            PeerTrustStore.Peer collector,
            PeerMeasurementPayload payload,
            List<String> localCandidateProfileIds) {
        if (collector == null
                || payload == null
                || !payload.requiresClaim) {
            return false;
        }

        RemotePendingMeasurementStore.Item incoming =
                new RemotePendingMeasurementStore.Item(
                        payload.measurementId,
                        collector.deviceId,
                        payload.scaleMac,
                        payload.timestampMs,
                        payload.weightKg,
                        payload.impedanceHigh,
                        payload.impedanceLow,
                        payload.scaleProfileId,
                        localCandidateProfileIds,
                        System.currentTimeMillis());

        if (!incoming.isValid()) {
            return false;
        }

        RemotePendingMeasurementEntity existing =
                dao.find(
                        incoming.measurementId);

        if (existing != null) {
            if (!incoming.collectorDeviceId.equals(
                    existing.collectorDeviceId)) {
                return false;
            }

            return dao.update(
                    toEntity(
                            incoming,
                            existing.sortOrder)) == 1;
        }

        RemotePendingMeasurementEntity entity =
                toEntity(
                        incoming,
                        nextSortOrder(
                                dao));

        long inserted =
                dao.insert(
                        entity);

        if (inserted != -1L) {
            return true;
        }

        existing =
                dao.find(
                        incoming.measurementId);

        if (existing == null
                || !incoming.collectorDeviceId.equals(
                        existing.collectorDeviceId)) {
            return false;
        }

        return dao.update(
                toEntity(
                        incoming,
                        existing.sortOrder)) == 1;
    }

    static boolean remove(
            RemotePendingMeasurementDao dao,
            String measurementId) {
        if (measurementId == null
                || measurementId.isBlank()) {
            return false;
        }

        return dao.delete(
                measurementId) == 1;
    }

    static int removeCollector(
            RemotePendingMeasurementDao dao,
            String collectorDeviceId) {
        if (!PeerTrustStore.isValidDeviceId(
                collectorDeviceId)) {
            return 0;
        }

        return dao.deleteCollector(
                collectorDeviceId);
    }

    static boolean migrateLegacyForTest(
            SharedPreferences preferences,
            RemotePendingMeasurementDao dao) {
        LegacyData legacy =
                parseLegacy(
                        preferences);

        if (!legacy.valid
                || !importLegacy(
                        dao,
                        legacy)) {
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

    private static <T> T runRoom(
            Context context,
            DatabaseOperation<T> operation) {
        if (context == null
                || operation == null) {
            throw new IllegalArgumentException(
                    "Context and operation are required");
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
                                    throw new IllegalStateException(
                                            "Remote pending migration failed");
                                }

                                return operation.run(
                                        database);
                            })
                    .get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Remote pending Room operation interrupted",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause =
                    exception.getCause();

            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }

            throw new IllegalStateException(
                    "Remote pending Room operation failed",
                    cause);
        }
    }

    private static boolean ensureLegacyMigrated(
            Context context,
            ScaleLauncherDatabase database) {
        if (migrationVerified) {
            return true;
        }

        SharedPreferences preferences =
                context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE);

        if (preferences.getBoolean(
                MIGRATION_KEY,
                false)) {
            migrationVerified = true;
            return true;
        }

        LegacyData legacy =
                parseLegacy(
                        preferences);

        if (!legacy.valid) {
            return false;
        }

        try {
            database.runInTransaction(
                    () -> {
                        if (!importLegacy(
                                database.remotePendingMeasurementDao(),
                                legacy)) {
                            throw new IllegalStateException(
                                    "Remote pending migration conflict");
                        }
                    });
        } catch (RuntimeException exception) {
            return false;
        }

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

        migrationVerified = true;
        return true;
    }

    private static LegacyData parseLegacy(
            SharedPreferences preferences) {
        if (preferences == null) {
            return invalidLegacy();
        }

        String encoded =
                preferences.getString(
                        KEY,
                        "");

        if (encoded == null
                || encoded.isBlank()) {
            return new LegacyData(
                    true,
                    List.of());
        }

        try {
            JSONArray array =
                    new JSONArray(
                            encoded);

            Map<String, RemotePendingMeasurementEntity> byId =
                    new LinkedHashMap<>();

            for (int index = 0;
                 index < array.length();
                 index++) {
                JSONObject object =
                        array.optJSONObject(
                                index);

                if (object == null) {
                    return invalidLegacy();
                }

                RemotePendingMeasurementStore.Item item =
                        RemotePendingMeasurementStore.Item.fromJson(
                                object);

                if (item == null) {
                    return invalidLegacy();
                }

                RemotePendingMeasurementEntity entity =
                        toEntity(
                                item,
                                index);

                RemotePendingMeasurementEntity previous =
                        byId.get(
                                entity.measurementId);

                if (previous != null) {
                    if (!sameData(
                            previous,
                            entity)) {
                        return invalidLegacy();
                    }

                    continue;
                }

                byId.put(
                        entity.measurementId,
                        entity);
            }

            return new LegacyData(
                    true,
                    new ArrayList<>(
                            byId.values()));
        } catch (JSONException exception) {
            return invalidLegacy();
        }
    }

    private static boolean importLegacy(
            RemotePendingMeasurementDao dao,
            LegacyData legacy) {
        for (RemotePendingMeasurementEntity incoming :
                legacy.items) {
            RemotePendingMeasurementEntity existing =
                    dao.find(
                            incoming.measurementId);

            if (existing != null) {
                if (!sameData(
                        existing,
                        incoming)) {
                    return false;
                }

                continue;
            }

            long inserted =
                    dao.insert(
                            incoming);

            if (inserted != -1L) {
                continue;
            }

            existing =
                    dao.find(
                            incoming.measurementId);

            if (existing == null
                    || !sameData(
                            existing,
                            incoming)) {
                return false;
            }
        }

        return true;
    }

    private static LegacyData invalidLegacy() {
        return new LegacyData(
                false,
                List.of());
    }

    private static RemotePendingMeasurementEntity toEntity(
            RemotePendingMeasurementStore.Item item,
            long sortOrder) {
        return new RemotePendingMeasurementEntity(
                item.measurementId,
                item.collectorDeviceId,
                item.scaleMac,
                item.timestampMs,
                item.weightKg,
                item.impedanceHigh,
                item.impedanceLow,
                item.scaleProfileId,
                encodeIds(
                        item.candidateProfileIds),
                item.receivedAtMs,
                sortOrder);
    }

    private static RemotePendingMeasurementStore.Item fromEntity(
            RemotePendingMeasurementEntity entity) {
        RemotePendingMeasurementStore.Item item =
                new RemotePendingMeasurementStore.Item(
                        entity.measurementId,
                        entity.collectorDeviceId,
                        entity.scaleMac,
                        entity.timestampMs,
                        entity.weightKg,
                        entity.impedanceHigh,
                        entity.impedanceLow,
                        entity.scaleProfileId,
                        decodeIds(
                                entity.candidateProfileIdsJson),
                        entity.receivedAtMs);

        if (!item.isValid()) {
            throw new IllegalStateException(
                    "Invalid remote pending Room row");
        }

        return item;
    }

    private static String encodeIds(
            List<String> ids) {
        JSONArray array =
                new JSONArray();

        if (ids != null) {
            for (String id : ids) {
                array.put(
                        id);
            }
        }

        return array.toString();
    }

    private static List<String> decodeIds(
            String encoded) {
        List<String> result =
                new ArrayList<>();

        try {
            JSONArray array =
                    new JSONArray(
                            encoded == null
                                    ? "[]"
                                    : encoded);

            for (int index = 0;
                 index < array.length();
                 index++) {
                Object value =
                        array.opt(
                                index);

                if (!(value instanceof String)) {
                    throw new IllegalStateException(
                            "Invalid profile id list");
                }

                result.add(
                        (String) value);
            }

            return result;
        } catch (JSONException exception) {
            throw new IllegalStateException(
                    "Invalid profile id JSON",
                    exception);
        }
    }

    private static boolean sameData(
            RemotePendingMeasurementEntity first,
            RemotePendingMeasurementEntity second) {
        return first.measurementId.equals(
                        second.measurementId)
                && first.collectorDeviceId.equals(
                        second.collectorDeviceId)
                && first.scaleMac.equals(
                        second.scaleMac)
                && first.timestampMs
                        == second.timestampMs
                && Float.compare(
                        first.weightKg,
                        second.weightKg) == 0
                && Float.compare(
                        first.impedanceHigh,
                        second.impedanceHigh) == 0
                && Float.compare(
                        first.impedanceLow,
                        second.impedanceLow) == 0
                && Objects.equals(
                        first.scaleProfileId,
                        second.scaleProfileId)
                && decodeIds(
                        first.candidateProfileIdsJson)
                        .equals(
                                decodeIds(
                                        second.candidateProfileIdsJson))
                && first.receivedAtMs
                        == second.receivedAtMs;
    }

    private static long nextSortOrder(
            RemotePendingMeasurementDao dao) {
        long current =
                dao.maxSortOrder();

        if (current == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "Remote pending sort order exhausted");
        }

        return current + 1L;
    }
}
