package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.SharedPreferences;

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
        if (context == null
                || pendingId == null
                || pendingId.isBlank()) {
            return new Result(
                    Status.INVALID,
                    0f,
                    0);
        }

        SharedPreferences prefs =
                context.getSharedPreferences(
                        "prefs",
                        Context.MODE_PRIVATE);

        PendingMeasurementStore.Item pending =
                PendingMeasurementStore.find(
                        prefs,
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
                context,
                pendingId);

        int queued =
                0;

        for (PeerTrustStore.Peer peer :
                PeerTrustStore.load(
                        context)) {
            try {
                PeerMeasurementClosedPayload payload =
                        PeerMeasurementClosedPayload.create(
                                pendingId);

                PeerOutboxStore.enqueueClosed(
                        context,
                        peer.deviceId,
                        payload);

                queued++;
            } catch (RuntimeException exception) {
                EventLog.warning(
                        context,
                        context.getString(
                                R.string.log_peer_transport_error,
                                exception.getClass()
                                        .getSimpleName()));
            }
        }

        PendingMeasurementStore.remove(
                prefs,
                pendingId);

        if (queued > 0) {
            EventLog.debug(
                    context,
                    context.getString(
                            R.string.log_measurement_closed_queued,
                            pendingId,
                            queued));
        }

        EventLog.info(
                context,
                context.getString(
                        R.string.pending_discarded_log,
                        pending.weightKg));

        return new Result(
                Status.DISCARDED,
                pending.weightKg,
                queued);
    }
}
