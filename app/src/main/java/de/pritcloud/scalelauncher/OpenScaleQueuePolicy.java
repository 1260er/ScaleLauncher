package de.pritcloud.scalelauncher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class OpenScaleQueuePolicy {
    enum Outcome {
        STORED,
        FAILED
    }

    static final class Plan {
        final List<OpenScalePendingRoomStore.Item> attempts;
        final String currentMeasurementId;

        Plan(
                List<OpenScalePendingRoomStore.Item> attempts,
                String currentMeasurementId) {
            this.attempts =
                    List.copyOf(
                            attempts);
            this.currentMeasurementId =
                    currentMeasurementId;
        }
    }

    private OpenScaleQueuePolicy() {
    }

    static Plan plan(
            List<OpenScalePendingRoomStore.Item> queued,
            long currentUserId,
            String currentHouseholdProfileId,
            String currentMeasurementId) {
        if (queued == null
                || currentUserId < 0L
                || !UserProfile.isValidHouseholdProfileId(
                        currentHouseholdProfileId)
                || currentMeasurementId == null
                || currentMeasurementId.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid openScale queue plan");
        }

        long currentQueuedAtMs =
                -1L;

        for (OpenScalePendingRoomStore.Item item :
                queued) {
            if (item == null
                    || item.measurement == null
                    || item.userId != currentUserId
                    || !currentHouseholdProfileId.equals(
                            item.householdProfileId)
                    || !currentMeasurementId.equals(
                            item.measurement.measurementId)) {
                continue;
            }

            currentQueuedAtMs =
                    item.queuedAtMs;
            break;
        }

        if (currentQueuedAtMs <= 0L) {
            throw new IllegalStateException(
                    "Current openScale queue measurement missing");
        }

        List<OpenScalePendingRoomStore.Item> attempts =
                new ArrayList<>();

        boolean currentFound =
                false;

        for (OpenScalePendingRoomStore.Item item :
                queued) {
            if (item == null
                    || item.measurement == null
                    || item.userId != currentUserId
                    || !currentHouseholdProfileId.equals(
                            item.householdProfileId)) {
                continue;
            }

            if (currentMeasurementId.equals(
                    item.measurement.measurementId)) {
                attempts.add(
                        item);

                currentFound =
                        true;
                break;
            }

            /*
             * timestamp_ms determines chronological retry order, but
             * queued_at_ms defines the request batch boundary. A measurement
             * queued after the current request must never enter that earlier
             * request merely because its physical timestamp is older.
             *
             * Equal queued_at_ms is treated conservatively as outside the
             * batch because insertion order cannot be proven from the
             * millisecond timestamp alone.
             */
            if (item.queuedAtMs <= 0L
                    || item.queuedAtMs
                    >= currentQueuedAtMs) {
                continue;
            }

            attempts.add(
                    item);
        }

        if (!currentFound) {
            throw new IllegalStateException(
                    "Current openScale queue measurement missing");
        }

        return new Plan(
                attempts,
                currentMeasurementId);
    }

    static Set<String> removalsAfterRun(
            Plan plan,
            List<Outcome> outcomes) {
        if (plan == null
                || outcomes == null
                || outcomes.size() != plan.attempts.size()
                || plan.attempts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid openScale queue outcomes");
        }

        int currentIndex =
                plan.attempts.size() - 1;

        OpenScalePendingRoomStore.Item current =
                plan.attempts.get(
                        currentIndex);

        if (!plan.currentMeasurementId.equals(
                current.measurement.measurementId)) {
            throw new IllegalStateException(
                    "Current openScale queue measurement is not last");
        }

        for (Outcome outcome : outcomes) {
            if (outcome == null) {
                throw new IllegalArgumentException(
                        "Missing openScale queue outcome");
            }
        }

        if (outcomes.get(currentIndex)
                != Outcome.STORED) {
            return Set.of();
        }

        Set<String> removals =
                new LinkedHashSet<>();

        for (OpenScalePendingRoomStore.Item item :
                plan.attempts) {
            removals.add(
                    item.measurement.measurementId);
        }

        return removals;
    }
}
