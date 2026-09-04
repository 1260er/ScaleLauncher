package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

final class PendingMeasurementCleanup {

    enum Status {
        DISCARDED,
        MISSING,
        ALREADY_RESOLVED,
        INVALID
    }

    static final class Result {
        final Status status;
        final float weightKg;
        final int closedQueued;

        Result(
                Status status,
                float weightKg,
                int closedQueued) {
            this.status = status;
            this.weightKg = weightKg;
            this.closedQueued = closedQueued;
        }
    }

    private PendingMeasurementCleanup() {
    }

    static Result discardLocal(
            Context context,
            String pendingId) {
        if (context == null) {
            return new Result(
                    Status.INVALID,
                    0f,
                    0);
        }

        List<String> peerDeviceIds =
                new ArrayList<>();

        for (PeerTrustStore.Peer peer :
                PeerTrustStore.load(
                        context)) {
            peerDeviceIds.add(
                    peer.deviceId);
        }

        Result result =
                discardLocalRoom(
                        context,
                        peerDeviceIds,
                        pendingId,
                        exception ->
                                EventLog.warning(
                                        context,
                                        context.getString(
                                                R.string.log_peer_transport_error,
                                                exception.getClass()
                                                        .getSimpleName())));

        if (result.status
                == Status.DISCARDED) {
            if (result.closedQueued > 0) {
                EventLog.debug(
                        context,
                        context.getString(
                                R.string.log_measurement_closed_queued,
                                pendingId,
                                result.closedQueued));
            }

            EventLog.info(
                    context,
                    context.getString(
                            R.string.pending_discarded_log,
                            result.weightKg));
        }

        return result;
    }

    private static Result discardLocalRoom(
            Context context,
            List<String> peerDeviceIds,
            String pendingId,
            ErrorHandler errorHandler) {
        if (context == null
                || peerDeviceIds == null
                || pendingId == null
                || pendingId.isBlank()) {
            return new Result(
                    Status.INVALID,
                    0f,
                    0);
        }

        PendingMeasurementStore.Item pending =
                PendingMeasurementRoomStore.find(
                        context,
                        pendingId);

        if (pending == null) {
            return new Result(
                    Status.MISSING,
                    0f,
                    0);
        }

        if (pending.isResolved()) {
            return new Result(
                    Status.ALREADY_RESOLVED,
                    pending.weightKg,
                    0);
        }

        PeerOutboxRoomStore.removeMeasurement(
                context,
                pendingId);

        int queued = 0;

        for (String peerDeviceId :
                peerDeviceIds) {
            try {
                PeerMeasurementClosedPayload payload =
                        PeerMeasurementClosedPayload.create(
                                pendingId);

                PeerOutboxRoomStore.enqueueClosed(
                        context,
                        peerDeviceId,
                        payload);

                queued++;
            } catch (RuntimeException exception) {
                errorHandler.onError(
                        exception);
            }
        }

        PendingMeasurementRoomStore.remove(
                context,
                pendingId);

        return new Result(
                Status.DISCARDED,
                pending.weightKg,
                queued);
    }

    static Result discardLocal(
            SharedPreferences pendingPreferences,
            SharedPreferences outboxPreferences,
            List<String> peerDeviceIds,
            String pendingId) {
        return discardLocal(
                pendingPreferences,
                outboxPreferences,
                peerDeviceIds,
                pendingId,
                exception -> {
                });
    }

    private static Result discardLocal(
            SharedPreferences pendingPreferences,
            SharedPreferences outboxPreferences,
            List<String> peerDeviceIds,
            String pendingId,
            ErrorHandler errorHandler) {
        if (pendingPreferences == null
                || outboxPreferences == null
                || peerDeviceIds == null
                || pendingId == null
                || pendingId.isBlank()) {
            return new Result(
                    Status.INVALID,
                    0f,
                    0);
        }

        PendingMeasurementStore.Item pending =
                PendingMeasurementStore.find(
                        pendingPreferences,
                        pendingId);

        if (pending == null) {
            return new Result(
                    Status.MISSING,
                    0f,
                    0);
        }

        if (pending.isResolved()) {
            return new Result(
                    Status.ALREADY_RESOLVED,
                    pending.weightKg,
                    0);
        }

        PeerOutboxStore.removeMeasurement(
                outboxPreferences,
                pendingId);

        int queued =
                0;

        for (String peerDeviceId :
                peerDeviceIds) {
            try {
                PeerMeasurementClosedPayload payload =
                        PeerMeasurementClosedPayload.create(
                                pendingId);

                PeerOutboxStore.enqueueClosed(
                        outboxPreferences,
                        peerDeviceId,
                        payload);

                queued++;
            } catch (RuntimeException exception) {
                errorHandler.onError(
                        exception);
            }
        }

        PendingMeasurementStore.remove(
                pendingPreferences,
                pendingId);

        return new Result(
                Status.DISCARDED,
                pending.weightKg,
                queued);
    }

    private interface ErrorHandler {
        void onError(
                RuntimeException exception);
    }
}
