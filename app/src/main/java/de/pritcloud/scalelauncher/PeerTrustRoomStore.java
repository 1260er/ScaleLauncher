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

final class PeerTrustRoomStore {
    private static final String PREFS =
            "peer_trust_v1";

    private static final String KEY =
            "trusted_peers";

    private static final String MIGRATION_KEY =
            "peer_trust_room_migration_complete_v1";

    private static final ExecutorService DB_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread =
                                new Thread(
                                        runnable,
                                        "ScaleLauncherPeerTrust");

                        thread.setDaemon(
                                true);

                        return thread;
                    });

    private static volatile boolean migrationVerified;

    private interface DatabaseOperation<T> {
        T run(
                ScaleLauncherDatabase database);
    }

    private static final class LegacyData {
        final boolean valid;
        final List<PeerTrustEntity> items;

        LegacyData(
                boolean valid,
                List<PeerTrustEntity> items) {
            this.valid = valid;
            this.items = items;
        }
    }

    private PeerTrustRoomStore() {
    }

    static List<PeerTrustStore.Peer> load(
            Context context) {
        return runRoom(
                context,
                database ->
                        load(
                                database.peerTrustDao()));
    }

    static PeerTrustStore.Peer find(
            Context context,
            String deviceId) {
        return runRoom(
                context,
                database ->
                        find(
                                database.peerTrustDao(),
                                deviceId));
    }

    static boolean isTrusted(
            Context context,
            String deviceId) {
        return find(
                context,
                deviceId) != null;
    }

    static int count(
            Context context) {
        return runRoom(
                context,
                database ->
                        count(
                                database.peerTrustDao()));
    }

    static void trust(
            Context context,
            String deviceId,
            String label,
            byte[] sharedSecret) {
        if (!PeerTrustStore.isValidDeviceId(
                        deviceId)
                || sharedSecret == null
                || sharedSecret.length != 32) {
            throw new IllegalArgumentException(
                    "Invalid peer trust data");
        }

        String encryptedSecret =
                PeerTrustStore.encryptSecret(
                        sharedSecret);

        runRoom(
                context,
                database -> {
                    database.runInTransaction(
                            () ->
                                    trustEncrypted(
                                            database.peerTrustDao(),
                                            deviceId,
                                            label,
                                            encryptedSecret));

                    return null;
                });
    }

    static void remove(
            Context context,
            String deviceId) {
        runRoom(
                context,
                database -> {
                    remove(
                            database.peerTrustDao(),
                            deviceId);

                    return null;
                });

        PeerOutboxRoomStore.removePeer(
                context,
                deviceId);

        PeerInboxDedupRoomStore.removePeer(
                context,
                deviceId);

        HouseholdProfileRoomStore.removeOwner(
                context,
                deviceId);

        RemotePendingMeasurementRoomStore.removeCollector(
                context,
                deviceId);
    }

    static List<PeerTrustStore.Peer> load(
            PeerTrustDao dao) {
        if (dao == null) {
            throw new IllegalArgumentException(
                    "Peer trust DAO is required");
        }

        List<PeerTrustStore.Peer> result =
                new ArrayList<>();

        for (PeerTrustEntity entity :
                dao.loadAll()) {
            PeerTrustStore.Peer peer =
                    fromEntity(
                            entity);

            if (peer != null) {
                result.add(
                        peer);
            }
        }

        return result;
    }

    static PeerTrustStore.Peer find(
            PeerTrustDao dao,
            String deviceId) {
        if (dao == null
                || !PeerTrustStore.isValidDeviceId(
                        deviceId)) {
            return null;
        }

        PeerTrustEntity entity =
                dao.find(
                        deviceId);

        return entity == null
                ? null
                : fromEntity(
                        entity);
    }

    static int count(
            PeerTrustDao dao) {
        return load(
                dao).size();
    }

    static void trustEncrypted(
            PeerTrustDao dao,
            String deviceId,
            String label,
            String encryptedSecret) {
        if (dao == null
                || !PeerTrustStore.isValidDeviceId(
                        deviceId)
                || !isStoredSecret(
                        encryptedSecret)) {
            throw new IllegalArgumentException(
                    "Invalid encrypted peer trust data");
        }

        String storedLabel =
                label == null
                        ? ""
                        : label;

        PeerTrustEntity existing =
                dao.find(
                        deviceId);

        if (existing != null) {
            PeerTrustEntity replacement =
                    new PeerTrustEntity(
                            deviceId,
                            storedLabel,
                            encryptedSecret,
                            existing.sortOrder);

            if (dao.update(
                    replacement) != 1) {
                throw new IllegalStateException(
                        "Peer trust update failed");
            }

            return;
        }

        PeerTrustEntity created =
                new PeerTrustEntity(
                        deviceId,
                        storedLabel,
                        encryptedSecret,
                        nextSortOrder(
                                dao));

        long inserted =
                dao.insert(
                        created);

        if (inserted != -1L) {
            return;
        }

        existing =
                dao.find(
                        deviceId);

        if (existing == null) {
            throw new IllegalStateException(
                    "Peer trust insert failed");
        }

        PeerTrustEntity replacement =
                new PeerTrustEntity(
                        deviceId,
                        storedLabel,
                        encryptedSecret,
                        existing.sortOrder);

        if (dao.update(
                replacement) != 1) {
            throw new IllegalStateException(
                    "Peer trust update after insert race failed");
        }
    }

    static int remove(
            PeerTrustDao dao,
            String deviceId) {
        if (dao == null
                || deviceId == null) {
            return 0;
        }

        return dao.delete(
                deviceId);
    }

    static boolean migrateLegacyForTest(
            SharedPreferences preferences,
            PeerTrustDao dao) {
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
                                            "Peer trust migration failed");
                                }

                                return operation.run(
                                        database);
                            })
                    .get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Peer trust Room operation interrupted",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause =
                    exception.getCause();

            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }

            throw new IllegalStateException(
                    "Peer trust Room operation failed",
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
                                database.peerTrustDao(),
                                legacy)) {
                            throw new IllegalStateException(
                                    "Peer trust migration conflict");
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

            Map<String, PeerTrustEntity> byId =
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

                String deviceId =
                        object.optString(
                                "deviceId",
                                "");

                String label =
                        object.optString(
                                "label",
                                "");

                String encryptedSecret =
                        object.optString(
                                "secret",
                                "");

                if (!PeerTrustStore.isValidDeviceId(
                                deviceId)
                        || !isStoredSecret(
                                encryptedSecret)) {
                    return invalidLegacy();
                }

                if (byId.containsKey(
                        deviceId)) {
                    return invalidLegacy();
                }

                byId.put(
                        deviceId,
                        new PeerTrustEntity(
                                deviceId,
                                label,
                                encryptedSecret,
                                index));
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
            PeerTrustDao dao,
            LegacyData legacy) {
        if (dao == null
                || legacy == null
                || !legacy.valid) {
            return false;
        }

        for (PeerTrustEntity incoming :
                legacy.items) {
            PeerTrustEntity existing =
                    dao.find(
                            incoming.deviceId);

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
                            incoming.deviceId);

            if (existing == null
                    || !sameStoredData(
                            existing,
                            incoming)) {
                return false;
            }
        }

        return dao.count()
                == legacy.items.size();
    }

    private static PeerTrustStore.Peer fromEntity(
            PeerTrustEntity entity) {
        if (entity == null
                || !PeerTrustStore.isValidDeviceId(
                        entity.deviceId)
                || !isStoredSecret(
                        entity.encryptedSecret)) {
            return null;
        }

        byte[] sharedSecret;

        try {
            sharedSecret =
                    PeerTrustStore.decryptSecret(
                            entity.encryptedSecret);
        } catch (RuntimeException exception) {
            return null;
        }

        if (sharedSecret.length != 32) {
            return null;
        }

        return new PeerTrustStore.Peer(
                entity.deviceId,
                entity.label,
                sharedSecret);
    }

    private static boolean isStoredSecret(
            String encoded) {
        if (encoded == null
                || encoded.isBlank()) {
            return false;
        }

        int separator =
                encoded.indexOf(
                        ".");

        return separator > 0
                && separator
                        < encoded.length() - 1
                && encoded.indexOf(
                        ".",
                        separator + 1) < 0;
    }

    private static long nextSortOrder(
            PeerTrustDao dao) {
        Long maximum =
                dao.maxSortOrder();

        if (maximum == null) {
            return 0L;
        }

        if (maximum == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "Peer trust sort order exhausted");
        }

        return maximum + 1L;
    }

    private static boolean sameStoredData(
            PeerTrustEntity first,
            PeerTrustEntity second) {
        return first.deviceId.equals(
                        second.deviceId)
                && first.label.equals(
                        second.label)
                && first.encryptedSecret.equals(
                        second.encryptedSecret)
                && first.sortOrder
                        == second.sortOrder;
    }

    private static LegacyData invalidLegacy() {
        return new LegacyData(
                false,
                List.of());
    }
}
