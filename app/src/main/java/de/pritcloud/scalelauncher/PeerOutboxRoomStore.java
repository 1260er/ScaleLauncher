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

final class PeerOutboxRoomStore {
    private static final String PREFS =
            "peer_outbox_v1";

    private static final String KEY =
            "items";

    private static final String MIGRATION_KEY =
            "peer_outbox_room_migration_complete_v1";

    private static final ExecutorService DB_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread =
                                new Thread(
                                        runnable,
                                        "ScaleLauncherPeerOutbox");
                        thread.setDaemon(true);
                        return thread;
                    });

    private static volatile boolean migrationVerified;

    private interface DatabaseOperation<T> {
        T run(ScaleLauncherDatabase database);
    }

    private static final class LegacyData {
        final boolean valid;
        final List<PeerOutboxEntity> items;

        LegacyData(
                boolean valid,
                List<PeerOutboxEntity> items) {
            this.valid = valid;
            this.items = items;
        }
    }

    private PeerOutboxRoomStore() {
    }

    static void enqueueProfile(
            Context context,
            String peerDeviceId,
            PeerProfilePayload payload) {
        if (!PeerTrustStore.isValidDeviceId(peerDeviceId)
                || payload == null
                || !payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid profile outbox item");
        }

        enqueue(
                context,
                new PeerOutboxStore.Item(
                        payload.messageId,
                        peerDeviceId,
                        PeerOutboxStore.KIND_PROFILE,
                        payload.profile.profileId,
                        payload.encode(),
                        System.currentTimeMillis()),
                true);
    }

    static void enqueueProfileManifest(
            Context context,
            String peerDeviceId,
            PeerProfileManifestPayload payload) {
        if (!PeerTrustStore.isValidDeviceId(peerDeviceId)
                || payload == null
                || !payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid profile manifest outbox item");
        }

        enqueue(
                context,
                new PeerOutboxStore.Item(
                        payload.messageId,
                        peerDeviceId,
                        PeerOutboxStore.KIND_PROFILE_MANIFEST,
                        "owner-manifest",
                        payload.encode(),
                        System.currentTimeMillis()),
                true);
    }

    static void enqueueMeasurement(
            Context context,
            String peerDeviceId,
            PeerMeasurementPayload payload) {
        if (!PeerTrustStore.isValidDeviceId(peerDeviceId)
                || payload == null
                || !payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid measurement outbox item");
        }

        enqueue(
                context,
                new PeerOutboxStore.Item(
                        payload.transportMessageId(),
                        peerDeviceId,
                        PeerOutboxStore.KIND_MEASUREMENT,
                        payload.measurementId,
                        payload.encode(),
                        System.currentTimeMillis()),
                false);
    }

    static void enqueueClaim(
            Context context,
            String peerDeviceId,
            PeerClaimPayload payload) {
        if (!PeerTrustStore.isValidDeviceId(peerDeviceId)
                || payload == null
                || !payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid claim outbox item");
        }

        enqueue(
                context,
                new PeerOutboxStore.Item(
                        payload.messageId,
                        peerDeviceId,
                        PeerOutboxStore.KIND_CLAIM,
                        payload.measurementId,
                        payload.encode(),
                        System.currentTimeMillis()),
                true);
    }

    static void enqueueDecision(
            Context context,
            String peerDeviceId,
            PeerMeasurementDecisionPayload payload) {
        if (!PeerTrustStore.isValidDeviceId(peerDeviceId)
                || payload == null
                || !payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid measurement decision outbox item");
        }

        enqueue(
                context,
                new PeerOutboxStore.Item(
                        payload.messageId,
                        peerDeviceId,
                        PeerOutboxStore.KIND_DECISION,
                        payload.measurementId
                                + ":"
                                + payload.profileId,
                        payload.encode(),
                        System.currentTimeMillis()),
                true);
    }

    static void enqueueCollectorStatus(
            Context context,
            String peerDeviceId,
            PeerCollectorStatusPayload payload) {
        if (!PeerTrustStore.isValidDeviceId(peerDeviceId)
                || payload == null
                || !payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid collector status outbox item");
        }

        enqueue(
                context,
                new PeerOutboxStore.Item(
                        payload.messageId,
                        peerDeviceId,
                        PeerOutboxStore.KIND_COLLECTOR_STATUS,
                        "collector_status",
                        payload.encode(),
                        System.currentTimeMillis()),
                true);
    }

    static void enqueueClosed(
            Context context,
            String peerDeviceId,
            PeerMeasurementClosedPayload payload) {
        if (!PeerTrustStore.isValidDeviceId(peerDeviceId)
                || payload == null
                || !payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid closed measurement outbox item");
        }

        enqueue(
                context,
                new PeerOutboxStore.Item(
                        payload.messageId,
                        peerDeviceId,
                        PeerOutboxStore.KIND_CLOSED,
                        payload.measurementId,
                        payload.encode(),
                        System.currentTimeMillis()),
                true);
    }

    static List<PeerOutboxStore.Item> load(
            Context context) {
        return runRoom(
                context,
                database ->
                        load(
                                database.peerOutboxDao()));
    }

    static List<PeerOutboxStore.Item> forPeer(
            Context context,
            String peerDeviceId) {
        return runRoom(
                context,
                database ->
                        forPeer(
                                database.peerOutboxDao(),
                                peerDeviceId));
    }

    static boolean remove(
            Context context,
            String peerDeviceId,
            String messageId) {
        return runRoom(
                context,
                database ->
                        remove(
                                database.peerOutboxDao(),
                                peerDeviceId,
                                messageId));
    }

    static int count(
            Context context) {
        return runRoom(
                context,
                database ->
                        database.peerOutboxDao().count());
    }

    static int removeMeasurement(
            Context context,
            String measurementId) {
        return runRoom(
                context,
                database ->
                        removeMeasurement(
                                database.peerOutboxDao(),
                                measurementId));
    }

    static int removePeer(
            Context context,
            String peerDeviceId) {
        return runRoom(
                context,
                database ->
                        removePeer(
                                database.peerOutboxDao(),
                                peerDeviceId));
    }

    private static void enqueue(
            Context context,
            PeerOutboxStore.Item incoming,
            boolean coalesce) {
        runRoom(
                context,
                database -> {
                    database.runInTransaction(
                            () -> enqueue(
                                    database.peerOutboxDao(),
                                    incoming,
                                    coalesce));
                    return null;
                });
    }

    static void enqueue(
            PeerOutboxDao dao,
            PeerOutboxStore.Item incoming,
            boolean coalesce) {
        if (dao == null
                || !isValid(incoming)) {
            throw new IllegalArgumentException(
                    "Invalid peer outbox item");
        }

        PeerOutboxEntity existing =
                dao.find(
                        incoming.peerDeviceId,
                        incoming.messageId);

        if (existing != null) {
            return;
        }

        if (coalesce) {
            dao.deleteCoalesced(
                    incoming.peerDeviceId,
                    incoming.kind,
                    incoming.dedupKey);
        }

        PeerOutboxEntity entity =
                toEntity(
                        incoming,
                        nextSortOrder(dao));

        long inserted =
                dao.insert(entity);

        if (inserted != -1L) {
            return;
        }

        if (dao.find(
                incoming.peerDeviceId,
                incoming.messageId) != null) {
            return;
        }

        throw new IllegalStateException(
                "Peer outbox insert failed");
    }

    static List<PeerOutboxStore.Item> load(
            PeerOutboxDao dao) {
        List<PeerOutboxStore.Item> result =
                new ArrayList<>();

        for (PeerOutboxEntity entity :
                dao.loadAll()) {
            result.add(
                    fromEntity(entity));
        }

        return result;
    }

    static List<PeerOutboxStore.Item> forPeer(
            PeerOutboxDao dao,
            String peerDeviceId) {
        List<PeerOutboxStore.Item> result =
                new ArrayList<>();

        if (peerDeviceId == null) {
            return result;
        }

        for (PeerOutboxEntity entity :
                dao.loadForPeer(peerDeviceId)) {
            result.add(
                    fromEntity(entity));
        }

        return result;
    }

    static boolean remove(
            PeerOutboxDao dao,
            String peerDeviceId,
            String messageId) {
        if (peerDeviceId == null
                || messageId == null) {
            return false;
        }

        return dao.delete(
                peerDeviceId,
                messageId) == 1;
    }

    static int removeMeasurement(
            PeerOutboxDao dao,
            String measurementId) {
        if (measurementId == null
                || measurementId.isBlank()) {
            return 0;
        }

        return dao.deleteMeasurement(
                measurementId,
                measurementId + ":");
    }

    static int removePeer(
            PeerOutboxDao dao,
            String peerDeviceId) {
        if (peerDeviceId == null) {
            return 0;
        }

        return dao.deletePeer(
                peerDeviceId);
    }

    static boolean migrateLegacyForTest(
            SharedPreferences preferences,
            PeerOutboxDao dao) {
        LegacyData legacy =
                parseLegacy(preferences);

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
                                            "Peer outbox migration failed");
                                }

                                return operation.run(
                                        database);
                            })
                    .get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Peer outbox Room operation interrupted",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause =
                    exception.getCause();

            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }

            throw new IllegalStateException(
                    "Peer outbox Room operation failed",
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
                parseLegacy(preferences);

        if (!legacy.valid) {
            return false;
        }

        try {
            database.runInTransaction(
                    () -> {
                        if (!importLegacy(
                                database.peerOutboxDao(),
                                legacy)) {
                            throw new IllegalStateException(
                                    "Peer outbox migration conflict");
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
                    new JSONArray(encoded);

            Map<String, PeerOutboxEntity> byKey =
                    new LinkedHashMap<>();

            for (int index = 0;
                 index < array.length();
                 index++) {
                JSONObject object =
                        array.optJSONObject(index);

                if (object == null) {
                    return invalidLegacy();
                }

                PeerOutboxStore.Item item =
                        new PeerOutboxStore.Item(
                                object.optString(
                                        "messageId",
                                        ""),
                                object.optString(
                                        "peerDeviceId",
                                        ""),
                                object.optString(
                                        "kind",
                                        ""),
                                object.optString(
                                        "dedupKey",
                                        ""),
                                object.optString(
                                        "payload",
                                        ""),
                                object.optLong(
                                        "createdAtMs",
                                        0L));

                if (!isValid(item)) {
                    return invalidLegacy();
                }

                PeerOutboxEntity entity =
                        toEntity(
                                item,
                                index);

                String key =
                        entity.peerDeviceId
                                + "\u0000"
                                + entity.messageId;

                PeerOutboxEntity previous =
                        byKey.get(key);

                if (previous != null) {
                    if (!sameContent(
                            previous,
                            entity)) {
                        return invalidLegacy();
                    }

                    continue;
                }

                byKey.put(
                        key,
                        entity);
            }

            return new LegacyData(
                    true,
                    new ArrayList<>(
                            byKey.values()));
        } catch (JSONException exception) {
            return invalidLegacy();
        }
    }

    private static boolean importLegacy(
            PeerOutboxDao dao,
            LegacyData legacy) {
        for (PeerOutboxEntity incoming :
                legacy.items) {
            PeerOutboxEntity existing =
                    dao.find(
                            incoming.peerDeviceId,
                            incoming.messageId);

            if (existing != null) {
                if (!sameStoredData(
                        existing,
                        incoming)) {
                    return false;
                }

                continue;
            }

            long inserted =
                    dao.insert(incoming);

            if (inserted != -1L) {
                continue;
            }

            existing =
                    dao.find(
                            incoming.peerDeviceId,
                            incoming.messageId);

            if (existing == null
                    || !sameStoredData(
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

    private static PeerOutboxEntity toEntity(
            PeerOutboxStore.Item item,
            long sortOrder) {
        return new PeerOutboxEntity(
                item.peerDeviceId,
                item.messageId,
                item.kind,
                item.dedupKey,
                item.payload,
                item.createdAtMs,
                sortOrder);
    }

    private static PeerOutboxStore.Item fromEntity(
            PeerOutboxEntity entity) {
        PeerOutboxStore.Item item =
                new PeerOutboxStore.Item(
                        entity.messageId,
                        entity.peerDeviceId,
                        entity.kind,
                        entity.dedupKey,
                        entity.payload,
                        entity.createdAtMs);

        if (!isValid(item)) {
            throw new IllegalStateException(
                    "Invalid peer outbox Room row");
        }

        return item;
    }

    private static boolean isValid(
            PeerOutboxStore.Item item) {
        return item != null
                && item.messageId != null
                && !item.messageId.isBlank()
                && item.messageId.length() <= 200
                && PeerTrustStore.isValidDeviceId(
                        item.peerDeviceId)
                && (PeerOutboxStore.KIND_PROFILE.equals(
                        item.kind)
                    || PeerOutboxStore.KIND_PROFILE_MANIFEST.equals(
                        item.kind)
                    || PeerOutboxStore.KIND_MEASUREMENT.equals(
                        item.kind)
                    || PeerOutboxStore.KIND_CLAIM.equals(
                        item.kind)
                    || PeerOutboxStore.KIND_DECISION.equals(
                        item.kind)
                    || PeerOutboxStore.KIND_CLOSED.equals(
                        item.kind)
                    || PeerOutboxStore.KIND_COLLECTOR_STATUS.equals(
                        item.kind))
                && item.dedupKey != null
                && !item.dedupKey.isBlank()
                && item.payload != null
                && !item.payload.isBlank()
                && item.payload.length() <= 16384
                && item.createdAtMs > 0L;
    }

    private static boolean sameContent(
            PeerOutboxEntity first,
            PeerOutboxEntity second) {
        return first.peerDeviceId.equals(
                        second.peerDeviceId)
                && first.messageId.equals(
                        second.messageId)
                && first.kind.equals(
                        second.kind)
                && first.dedupKey.equals(
                        second.dedupKey)
                && first.payload.equals(
                        second.payload)
                && first.createdAtMs
                        == second.createdAtMs;
    }

    private static boolean sameStoredData(
            PeerOutboxEntity first,
            PeerOutboxEntity second) {
        return sameContent(
                        first,
                        second)
                && first.sortOrder
                        == second.sortOrder;
    }

    private static long nextSortOrder(
            PeerOutboxDao dao) {
        long current =
                dao.maxSortOrder();

        if (current == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "Peer outbox sort order exhausted");
        }

        return current + 1L;
    }
}
