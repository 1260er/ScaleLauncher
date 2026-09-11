package de.pritcloud.scalelauncher;

import android.content.Context;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class RoutedMeasurementAcceptanceRoomStore {
    private static final ExecutorService DB_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread =
                                new Thread(
                                        runnable,
                                        "ScaleLauncherRoutedAcceptance");

                        thread.setDaemon(true);
                        return thread;
                    });

    private interface DatabaseOperation<T> {
        T run(ScaleLauncherDatabase database);
    }

    private RoutedMeasurementAcceptanceRoomStore() {
    }

    static PeerInboxDedupRoomStore.FingerprintStatus accept(
            Context context,
            String senderDeviceId,
            long userId,
            PeerMeasurementPayload payload) {
        if (payload == null
                || !payload.isValid()
                || payload.requiresClaim) {
            throw new IllegalArgumentException(
                    "Invalid routed measurement acceptance");
        }

        long acceptedAtMs =
                System.currentTimeMillis();

        return runRoom(
                context,
                database ->
                        database.runInTransaction(
                                () ->
                                        accept(
                                                database.peerInboxDedupDao(),
                                                database.openScalePendingDao(),
                                                senderDeviceId,
                                                userId,
                                                payload,
                                                acceptedAtMs)));
    }

    static PeerInboxDedupRoomStore.FingerprintStatus accept(
            PeerInboxDedupDao dedupDao,
            OpenScalePendingDao pendingDao,
            String senderDeviceId,
            long userId,
            PeerMeasurementPayload payload,
            long acceptedAtMs) {
        if (dedupDao == null
                || pendingDao == null
                || !PeerTrustStore.isValidDeviceId(
                        senderDeviceId)
                || userId < 0L
                || payload == null
                || !payload.isValid()
                || payload.requiresClaim
                || acceptedAtMs <= 0L) {
            throw new IllegalArgumentException(
                    "Invalid routed measurement acceptance");
        }

        String dedupKey =
                "routed-measurement:"
                        + payload.measurementId;

        String fingerprint =
                payload.routedPayloadFingerprint();

        PeerInboxDedupEntity existing =
                dedupDao.find(
                        senderDeviceId,
                        dedupKey);

        if (existing != null) {
            return PeerInboxDedupRoomStore.compareFingerprint(
                    existing,
                    fingerprint);
        }

        OpenScalePendingRoomStore.add(
                pendingDao,
                userId,
                payload.targetProfileId,
                payload.toMeasurement(),
                acceptedAtMs);

        PeerInboxDedupRoomStore.FingerprintStatus status =
                PeerInboxDedupRoomStore.checkOrMarkFingerprint(
                        dedupDao,
                        senderDeviceId,
                        dedupKey,
                        fingerprint,
                        acceptedAtMs);

        if (status
                == PeerInboxDedupRoomStore.FingerprintStatus.NEW
                || status
                == PeerInboxDedupRoomStore.FingerprintStatus.MATCH) {
            return status;
        }

        /*
         * This can only happen if the row changed between the initial
         * lookup and the insert. The Context path runs inside one Room
         * transaction, so throwing here rolls the queue write back too.
         */
        throw new IllegalStateException(
                "Routed measurement acceptance conflict");
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
                            () ->
                                    operation.run(
                                            ScaleLauncherDatabase.get(
                                                    appContext)))
                    .get();
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();

            throw new IllegalStateException(
                    "Routed measurement Room operation interrupted",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause =
                    exception.getCause();

            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }

            throw new IllegalStateException(
                    "Routed measurement Room operation failed",
                    cause);
        }
    }
}
