package de.pritcloud.scalelauncher;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class OpenScalePendingRoomStore {
    private static final ExecutorService DB_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread =
                                new Thread(
                                        runnable,
                                        "ScaleLauncherOpenScalePending");

                        thread.setDaemon(true);
                        return thread;
                    });

    private interface DatabaseOperation<T> {
        T run(ScaleLauncherDatabase database);
    }

    static final class Item {
        final long userId;
        final String householdProfileId;
        final S400FinalMeasurement measurement;
        final long queuedAtMs;

        Item(
                long userId,
                String householdProfileId,
                S400FinalMeasurement measurement,
                long queuedAtMs) {
            this.userId = userId;
            this.householdProfileId = householdProfileId;
            this.measurement = measurement;
            this.queuedAtMs = queuedAtMs;
        }
    }

    private OpenScalePendingRoomStore() {
    }

    static Item add(
            Context context,
            long userId,
            String householdProfileId,
            S400FinalMeasurement measurement) {
        long queuedAtMs =
                System.currentTimeMillis();

        return runRoom(
                context,
                database ->
                        add(
                                database.openScalePendingDao(),
                                userId,
                                householdProfileId,
                                measurement,
                                queuedAtMs));
    }

    static Item find(
            Context context,
            String measurementId) {
        return runRoom(
                context,
                database ->
                        find(
                                database.openScalePendingDao(),
                                measurementId));
    }

    static List<Item> loadForProfile(
            Context context,
            String householdProfileId) {
        return runRoom(
                context,
                database ->
                        loadForProfile(
                                database.openScalePendingDao(),
                                householdProfileId));
    }

    static List<Item> loadAll(
            Context context) {
        return runRoom(
                context,
                database ->
                        loadAll(
                                database.openScalePendingDao()));
    }

    static boolean remove(
            Context context,
            String measurementId) {
        return runRoom(
                context,
                database ->
                        remove(
                                database.openScalePendingDao(),
                                measurementId));
    }

    static int removeAll(
            Context context,
            List<String> measurementIds) {
        return runRoom(
                context,
                database ->
                        removeAll(
                                database.openScalePendingDao(),
                                measurementIds));
    }

    static int count(
            Context context,
            String householdProfileId) {
        return runRoom(
                context,
                database ->
                        count(
                                database.openScalePendingDao(),
                                householdProfileId));
    }

    static Item add(
            OpenScalePendingDao dao,
            long userId,
            String householdProfileId,
            S400FinalMeasurement measurement,
            long queuedAtMs) {
        if (dao == null
                || !UserProfile.isValidHouseholdProfileId(
                        householdProfileId)
                || measurement == null
                || !measurement.isComplete()
                || queuedAtMs <= 0L) {
            throw new IllegalArgumentException(
                    "Invalid openScale pending measurement");
        }

        OpenScalePendingEntity incoming =
                new OpenScalePendingEntity(
                        measurement.measurementId,
                        userId,
                        householdProfileId,
                        measurement.weightKg,
                        measurement.impedanceHigh,
                        measurement.impedanceLow,
                        measurement.scaleProfileId,
                        measurement.timestampMs,
                        queuedAtMs);

        OpenScalePendingEntity existing =
                dao.find(
                        measurement.measurementId);

        if (existing != null) {
            if (!sameMeasurementIdentity(
                    existing,
                    incoming)) {
                throw conflict(
                        measurement.measurementId);
            }

            return fromEntity(
                    existing);
        }

        long inserted =
                dao.insert(
                        incoming);

        if (inserted != -1L) {
            return fromEntity(
                    incoming);
        }

        existing =
                dao.find(
                        measurement.measurementId);

        if (existing == null) {
            throw new IllegalStateException(
                    "openScale pending insert failed");
        }

        if (!sameMeasurementIdentity(
                existing,
                incoming)) {
            throw conflict(
                    measurement.measurementId);
        }

        return fromEntity(
                existing);
    }

    static Item find(
            OpenScalePendingDao dao,
            String measurementId) {
        if (dao == null
                || measurementId == null
                || measurementId.isBlank()) {
            return null;
        }

        OpenScalePendingEntity entity =
                dao.find(
                        measurementId);

        return entity == null
                ? null
                : fromEntity(entity);
    }

    static List<Item> loadForProfile(
            OpenScalePendingDao dao,
            String householdProfileId) {
        if (dao == null
                || !UserProfile.isValidHouseholdProfileId(
                        householdProfileId)) {
            return List.of();
        }

        List<Item> result =
                new ArrayList<>();

        for (OpenScalePendingEntity entity :
                dao.loadForProfile(
                        householdProfileId)) {
            result.add(
                    fromEntity(entity));
        }

        return result;
    }

    static List<Item> loadAll(
            OpenScalePendingDao dao) {
        if (dao == null) {
            return List.of();
        }

        List<Item> result =
                new ArrayList<>();

        for (OpenScalePendingEntity entity :
                dao.loadAll()) {
            result.add(
                    fromEntity(entity));
        }

        return result;
    }

    static boolean remove(
            OpenScalePendingDao dao,
            String measurementId) {
        if (dao == null
                || measurementId == null
                || measurementId.isBlank()) {
            return false;
        }

        return dao.delete(
                measurementId) == 1;
    }

    static int removeAll(
            OpenScalePendingDao dao,
            List<String> measurementIds) {
        if (dao == null
                || measurementIds == null
                || measurementIds.isEmpty()) {
            return 0;
        }

        LinkedHashSet<String> uniqueIds =
                new LinkedHashSet<>();

        for (String measurementId :
                measurementIds) {
            if (measurementId == null
                    || measurementId.isBlank()) {
                throw new IllegalArgumentException(
                        "Invalid openScale pending measurement ID");
            }

            uniqueIds.add(
                    measurementId);
        }

        return dao.deleteAll(
                new ArrayList<>(
                        uniqueIds));
    }

    static int count(
            OpenScalePendingDao dao,
            String householdProfileId) {
        if (dao == null
                || !UserProfile.isValidHouseholdProfileId(
                        householdProfileId)) {
            return 0;
        }

        return dao.countForProfile(
                householdProfileId);
    }

    private static Item fromEntity(
            OpenScalePendingEntity entity) {
        return new Item(
                entity.userId,
                entity.householdProfileId,
                new S400FinalMeasurement(
                        entity.measurementId,
                        entity.weightKg,
                        entity.impedanceHigh,
                        entity.impedanceLow,
                        entity.timestampMs,
                        entity.scaleProfileId),
                entity.queuedAtMs);
    }

    private static boolean sameMeasurementIdentity(
            OpenScalePendingEntity first,
            OpenScalePendingEntity second) {
        return first.measurementId.equals(
                        second.measurementId)
                && first.userId
                        == second.userId
                && first.householdProfileId.equals(
                        second.householdProfileId)
                && Float.compare(
                        first.weightKg,
                        second.weightKg) == 0
                && Float.compare(
                        first.impedanceHigh,
                        second.impedanceHigh) == 0
                && Objects.equals(
                        first.impedanceLow,
                        second.impedanceLow)
                && Objects.equals(
                        first.scaleProfileId,
                        second.scaleProfileId)
                && first.timestampMs
                        == second.timestampMs;
    }

    private static IllegalStateException conflict(
            String measurementId) {
        return new IllegalStateException(
                "Conflicting openScale pending measurement: "
                        + measurementId);
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
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "openScale pending Room operation interrupted",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause =
                    exception.getCause();

            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }

            throw new IllegalStateException(
                    "openScale pending Room operation failed",
                    cause);
        }
    }
}
