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

final class PeerInboxDedupRoomStore {
    private static final String PREFS =
            "peer_inbox_v1";

    private static final String KEY =
            "processed";

    private static final String MIGRATION_KEY =
            "peer_inbox_room_migration_complete_v1";

    private static final ExecutorService DB_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread =
                                new Thread(
                                        runnable,
                                        "ScaleLauncherPeerInbox");
                        thread.setDaemon(true);
                        return thread;
                    });

    private static volatile boolean migrationVerified;

    private interface DatabaseOperation<T> {
        T run(ScaleLauncherDatabase database);
    }

    private static final class LegacyData {
        final boolean valid;
        final List<PeerInboxDedupEntity> items;

        LegacyData(
                boolean valid,
                List<PeerInboxDedupEntity> items) {
            this.valid = valid;
            this.items = items;
        }
    }

    private PeerInboxDedupRoomStore() {
    }

    static boolean contains(
            Context context,
            String senderDeviceId,
            String messageId) {
        if (!isValidKey(
                senderDeviceId,
                messageId)) {
            return false;
        }

        return runRoom(
                context,
                database ->
                        contains(
                                database.peerInboxDedupDao(),
                                senderDeviceId,
                                messageId));
    }

    static void mark(
            Context context,
            String senderDeviceId,
            String messageId) {
        if (!isValidKey(
                senderDeviceId,
                messageId)) {
            return;
        }

        long seenAtMs =
                System.currentTimeMillis();

        runRoom(
                context,
                database -> {
                    mark(
                            database.peerInboxDedupDao(),
                            senderDeviceId,
                            messageId,
                            seenAtMs);

                    return null;
                });
    }

    static int removePeer(
            Context context,
            String peerDeviceId) {
        if (peerDeviceId == null) {
            return 0;
        }

        return runRoom(
                context,
                database ->
                        removePeer(
                                database.peerInboxDedupDao(),
                                peerDeviceId));
    }

    static int count(
            Context context) {
        return runRoom(
                context,
                database ->
                        database.peerInboxDedupDao()
                                .count());
    }

    static boolean contains(
            PeerInboxDedupDao dao,
            String senderDeviceId,
            String messageId) {
        if (dao == null
                || !isValidKey(
                        senderDeviceId,
                        messageId)) {
            return false;
        }

        return dao.contains(
                senderDeviceId,
                messageId);
    }

    static boolean mark(
            PeerInboxDedupDao dao,
            String senderDeviceId,
            String messageId,
            long seenAtMs) {
        if (dao == null
                || !isValid(
                        senderDeviceId,
                        messageId,
                        seenAtMs)) {
            return false;
        }

        long result =
                dao.upsert(
                        new PeerInboxDedupEntity(
                                senderDeviceId,
                                messageId,
                                seenAtMs));

        if (result == -1L) {
            throw new IllegalStateException(
                    "Peer inbox dedup upsert failed");
        }

        return true;
    }

    static int removePeer(
            PeerInboxDedupDao dao,
            String peerDeviceId) {
        if (dao == null
                || peerDeviceId == null) {
            return 0;
        }

        return dao.deletePeer(
                peerDeviceId);
    }

    static boolean migrateLegacyForTest(
            SharedPreferences preferences,
            PeerInboxDedupDao dao) {
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
        return preferences != null
                && preferences.getBoolean(
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
                                            "Peer inbox dedup migration failed");
                                }

                                return operation.run(
                                        database);
                            })
                    .get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Peer inbox dedup Room operation interrupted",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause =
                    exception.getCause();

            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }

            throw new IllegalStateException(
                    "Peer inbox dedup Room operation failed",
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
            migrationVerified =
                    true;

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
                                database.peerInboxDedupDao(),
                                legacy)) {
                            throw new IllegalStateException(
                                    "Peer inbox dedup migration conflict");
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

        migrationVerified =
                true;

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

            Map<String, PeerInboxDedupEntity> byKey =
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

                String senderDeviceId =
                        object.optString(
                                "senderDeviceId",
                                "");

                String messageId =
                        object.optString(
                                "messageId",
                                "");

                long seenAtMs =
                        object.optLong(
                                "seenAtMs",
                                0L);

                if (!isValid(
                        senderDeviceId,
                        messageId,
                        seenAtMs)) {
                    return invalidLegacy();
                }

                PeerInboxDedupEntity entity =
                        new PeerInboxDedupEntity(
                                senderDeviceId,
                                messageId,
                                seenAtMs);

                String key =
                        senderDeviceId
                                + "|"
                                + messageId;

                PeerInboxDedupEntity previous =
                        byKey.get(
                                key);

                if (previous != null) {
                    if (!sameStoredData(
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
            PeerInboxDedupDao dao,
            LegacyData legacy) {
        if (dao == null
                || legacy == null
                || !legacy.valid) {
            return false;
        }

        for (PeerInboxDedupEntity incoming :
                legacy.items) {
            PeerInboxDedupEntity existing =
                    dao.find(
                            incoming.senderDeviceId,
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
                    dao.insert(
                            incoming);

            if (inserted != -1L) {
                continue;
            }

            existing =
                    dao.find(
                            incoming.senderDeviceId,
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

    private static boolean isValidKey(
            String senderDeviceId,
            String messageId) {
        return PeerTrustStore.isValidDeviceId(
                        senderDeviceId)
                && messageId != null
                && !messageId.isBlank()
                && messageId.length() <= 200;
    }

    private static boolean isValid(
            String senderDeviceId,
            String messageId,
            long seenAtMs) {
        return isValidKey(
                        senderDeviceId,
                        messageId)
                && seenAtMs > 0L;
    }

    private static boolean sameStoredData(
            PeerInboxDedupEntity first,
            PeerInboxDedupEntity second) {
        return first.senderDeviceId.equals(
                        second.senderDeviceId)
                && first.messageId.equals(
                        second.messageId)
                && first.seenAtMs
                        == second.seenAtMs;
    }
}
