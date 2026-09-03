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

final class PendingMeasurementRoomStore {
    private static final String KEY =
            "pending_measurements_json";

    private static final String CLAIMS_KEY =
            "pending_measurement_claims_json";

    private static final String MIGRATION_KEY =
            "pending_measurements_room_migration_complete_v1";

    private static final ExecutorService DB_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread =
                                new Thread(
                                        runnable,
                                        "ScaleLauncherPending");

                        thread.setDaemon(true);
                        return thread;
                    });

    private static volatile boolean migrationVerified;

    private interface DatabaseOperation<T> {
        T run(ScaleLauncherDatabase database);
    }

    private static final class LegacyData {
        final boolean valid;
        final List<PendingMeasurementEntity> pending;
        final List<PendingMeasurementClaimEntity> claims;

        LegacyData(
                boolean valid,
                List<PendingMeasurementEntity> pending,
                List<PendingMeasurementClaimEntity> claims) {
            this.valid = valid;
            this.pending = pending;
            this.claims = claims;
        }
    }

    private PendingMeasurementRoomStore() {
    }

    static List<PendingMeasurementStore.Item> load(
            Context context) {
        return runRoom(
                context,
                database ->
                        load(
                                database.pendingMeasurementDao()));
    }

    static PendingMeasurementStore.Item find(
            Context context,
            String id) {
        return runRoom(
                context,
                database ->
                        find(
                                database.pendingMeasurementDao(),
                                id));
    }

    static PendingMeasurementStore.Item add(
            Context context,
            S400FinalMeasurement measurement,
            String reason) {
        return add(
                context,
                measurement,
                reason,
                List.of(),
                false);
    }

    static PendingMeasurementStore.Item add(
            Context context,
            S400FinalMeasurement measurement,
            String reason,
            List<String> candidateProfileIds) {
        return add(
                context,
                measurement,
                reason,
                candidateProfileIds,
                false);
    }

    static PendingMeasurementStore.Item add(
            Context context,
            S400FinalMeasurement measurement,
            String reason,
            List<String> candidateProfileIds,
            boolean manualRescue) {
        return runRoom(
                context,
                database ->
                        add(
                                database.pendingMeasurementDao(),
                                measurement,
                                reason,
                                candidateProfileIds,
                                manualRescue));
    }

    static boolean selectCandidate(
            Context context,
            String measurementId,
            String profileId,
            String ownerDeviceId) {
        return runRoom(
                context,
                database ->
                        selectCandidate(
                                database.pendingMeasurementDao(),
                                measurementId,
                                profileId,
                                ownerDeviceId));
    }

    static boolean rejectCandidate(
            Context context,
            String measurementId,
            String profileId) {
        return runRoom(
                context,
                database ->
                        rejectCandidate(
                                database.pendingMeasurementDao(),
                                measurementId,
                                profileId));
    }

    static boolean rejectSelectedCandidate(
            Context context,
            String measurementId,
            String profileId,
            String ownerDeviceId) {
        return runRoom(
                context,
                database ->
                        rejectSelectedCandidate(
                                database.pendingMeasurementDao(),
                                measurementId,
                                profileId,
                                ownerDeviceId));
    }

    static void recordClaimResponse(
            Context context,
            String measurementId,
            String peerDeviceId,
            List<String> profileIds) {
        runRoom(
                context,
                database -> {
                    recordClaimResponse(
                            database.pendingMeasurementDao(),
                            measurementId,
                            peerDeviceId,
                            profileIds);
                    return null;
                });
    }

    static int removeClaimResponsesForPeer(
            Context context,
            String peerDeviceId) {
        return runRoom(
                context,
                database ->
                        removeClaimResponsesForPeer(
                                database.pendingMeasurementDao(),
                                peerDeviceId));
    }

    static List<PendingMeasurementStore.ClaimResponse> claimResponses(
            Context context,
            String measurementId) {
        return runRoom(
                context,
                database ->
                        claimResponses(
                                database.pendingMeasurementDao(),
                                measurementId));
    }

    static void remove(
            Context context,
            String id) {
        runRoom(
                context,
                database -> {
                    database.runInTransaction(
                            () -> {
                                PendingMeasurementDao dao =
                                        database.pendingMeasurementDao();

                                dao.deleteClaimsForMeasurement(id);
                                dao.deletePending(id);
                            });

                    return null;
                });
    }

    static List<PendingMeasurementStore.Item> load(
            PendingMeasurementDao dao) {
        List<PendingMeasurementStore.Item> result =
                new ArrayList<>();

        for (PendingMeasurementEntity entity :
                dao.loadPending()) {
            result.add(
                    fromEntity(
                            entity));
        }

        return result;
    }

    static PendingMeasurementStore.Item find(
            PendingMeasurementDao dao,
            String id) {
        if (id == null
                || id.isBlank()) {
            return null;
        }

        PendingMeasurementEntity entity =
                dao.findPending(id);

        return entity == null
                ? null
                : fromEntity(entity);
    }

    static PendingMeasurementStore.Item add(
            PendingMeasurementDao dao,
            S400FinalMeasurement measurement,
            String reason,
            List<String> candidateProfileIds,
            boolean manualRescue) {
        if (measurement == null
                || measurement.measurementId == null
                || measurement.measurementId.isBlank()
                || measurement.weightKg <= 0f) {
            throw new IllegalArgumentException(
                    "Invalid pending measurement");
        }

        PendingMeasurementEntity existing =
                dao.findPending(
                        measurement.measurementId);

        if (existing != null) {
            return fromEntity(
                    existing);
        }

        PendingMeasurementStore.Item item =
                new PendingMeasurementStore.Item(
                        measurement.measurementId,
                        measurement.weightKg,
                        measurement.impedanceHigh,
                        measurement.impedanceLow,
                        measurement.scaleProfileId,
                        false,
                        measurement.timestampMs,
                        reason == null ? "" : reason,
                        manualRescue,
                        candidateProfileIds,
                        List.of(),
                        "",
                        "");

        PendingMeasurementEntity entity =
                toEntity(
                        item,
                        nextPendingOrder(
                                dao));

        long inserted =
                dao.insertPending(
                        entity);

        if (inserted != -1L) {
            return item;
        }

        existing =
                dao.findPending(
                        measurement.measurementId);

        if (existing == null) {
            throw new IllegalStateException(
                    "Pending measurement insert failed");
        }

        return fromEntity(
                existing);
    }

    static boolean selectCandidate(
            PendingMeasurementDao dao,
            String measurementId,
            String profileId,
            String ownerDeviceId) {
        if (measurementId == null
                || measurementId.isBlank()
                || !UserProfile.isValidHouseholdProfileId(
                        profileId)
                || !PeerTrustStore.isValidDeviceId(
                        ownerDeviceId)) {
            return false;
        }

        PendingMeasurementEntity entity =
                dao.findPending(
                        measurementId);

        if (entity == null) {
            return false;
        }

        PendingMeasurementStore.Item item =
                fromEntity(
                        entity);

        if (item.isResolved()
                || !item.candidateProfileIds.contains(
                        profileId)
                || item.rejectedProfileIds.contains(
                        profileId)) {
            return false;
        }

        PendingMeasurementStore.Item updated =
                copyWithDecision(
                        item,
                        item.rejectedProfileIds,
                        profileId,
                        ownerDeviceId);

        return dao.updatePending(
                toEntity(
                        updated,
                        entity.sortOrder)) == 1;
    }

    static boolean rejectCandidate(
            PendingMeasurementDao dao,
            String measurementId,
            String profileId) {
        if (measurementId == null
                || measurementId.isBlank()
                || !UserProfile.isValidHouseholdProfileId(
                        profileId)) {
            return false;
        }

        PendingMeasurementEntity entity =
                dao.findPending(
                        measurementId);

        if (entity == null) {
            return false;
        }

        PendingMeasurementStore.Item item =
                fromEntity(
                        entity);

        if (item.isResolved()
                || !item.candidateProfileIds.contains(
                        profileId)
                || item.rejectedProfileIds.contains(
                        profileId)) {
            return false;
        }

        List<String> rejected =
                new ArrayList<>(
                        item.rejectedProfileIds);

        rejected.add(
                profileId);

        PendingMeasurementStore.Item updated =
                copyWithDecision(
                        item,
                        rejected,
                        "",
                        "");

        return dao.updatePending(
                toEntity(
                        updated,
                        entity.sortOrder)) == 1;
    }

    static boolean rejectSelectedCandidate(
            PendingMeasurementDao dao,
            String measurementId,
            String profileId,
            String ownerDeviceId) {
        if (measurementId == null
                || measurementId.isBlank()
                || !UserProfile.isValidHouseholdProfileId(
                        profileId)
                || !PeerTrustStore.isValidDeviceId(
                        ownerDeviceId)) {
            return false;
        }

        PendingMeasurementEntity entity =
                dao.findPending(
                        measurementId);

        if (entity == null) {
            return false;
        }

        PendingMeasurementStore.Item item =
                fromEntity(
                        entity);

        if (!item.isResolved()
                || !profileId.equals(
                        item.selectedProfileId)
                || !ownerDeviceId.equals(
                        item.selectedOwnerDeviceId)) {
            return false;
        }

        List<String> rejected =
                new ArrayList<>(
                        item.rejectedProfileIds);

        if (!rejected.contains(
                profileId)) {
            rejected.add(
                    profileId);
        }

        PendingMeasurementStore.Item updated =
                copyWithDecision(
                        item,
                        rejected,
                        "",
                        "");

        return dao.updatePending(
                toEntity(
                        updated,
                        entity.sortOrder)) == 1;
    }

    static void recordClaimResponse(
            PendingMeasurementDao dao,
            String measurementId,
            String peerDeviceId,
            List<String> profileIds) {
        PendingMeasurementStore.ClaimResponse response =
                new PendingMeasurementStore.ClaimResponse(
                        measurementId,
                        peerDeviceId,
                        profileIds,
                        System.currentTimeMillis());

        if (!response.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid pending claim response");
        }

        PendingMeasurementClaimEntity entity =
                new PendingMeasurementClaimEntity(
                        response.measurementId,
                        response.peerDeviceId,
                        encodeIds(
                                response.profileIds),
                        response.updatedAtMs,
                        nextClaimOrder(
                                dao));

        dao.upsertClaim(
                entity);
    }

    static int removeClaimResponsesForPeer(
            PendingMeasurementDao dao,
            String peerDeviceId) {
        if (!PeerTrustStore.isValidDeviceId(
                peerDeviceId)) {
            return 0;
        }

        return dao.deleteClaimsForPeer(
                peerDeviceId);
    }

    static List<PendingMeasurementStore.ClaimResponse> claimResponses(
            PendingMeasurementDao dao,
            String measurementId) {
        List<PendingMeasurementStore.ClaimResponse> result =
                new ArrayList<>();

        if (measurementId == null
                || measurementId.isBlank()) {
            return result;
        }

        for (PendingMeasurementClaimEntity entity :
                dao.loadClaims(
                        measurementId)) {
            result.add(
                    fromEntity(
                            entity));
        }

        return result;
    }

    static void remove(
            PendingMeasurementDao dao,
            String id) {
        dao.deleteClaimsForMeasurement(
                id);
        dao.deletePending(
                id);
    }

    static boolean migrateLegacyForTest(
            SharedPreferences preferences,
            PendingMeasurementDao dao) {
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
                                            "Pending measurement migration failed");
                                }

                                return operation.run(
                                        database);
                            })
                    .get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Pending Room operation interrupted",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause =
                    exception.getCause();

            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }

            throw new IllegalStateException(
                    "Pending Room operation failed",
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
                        "prefs",
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
                                database.pendingMeasurementDao(),
                                legacy)) {
                            throw new IllegalStateException(
                                    "Pending migration conflict");
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
        List<PendingMeasurementEntity> pending =
                new ArrayList<>();

        List<PendingMeasurementClaimEntity> claims =
                new ArrayList<>();

        try {
            Map<String, PendingMeasurementEntity> pendingById =
                    new LinkedHashMap<>();

            String pendingEncoded =
                    preferences.getString(
                            KEY,
                            "");

            if (pendingEncoded != null
                    && !pendingEncoded.isBlank()) {
                JSONArray array =
                        new JSONArray(
                                pendingEncoded);

                for (int index = 0;
                     index < array.length();
                     index++) {
                    JSONObject object =
                            array.optJSONObject(
                                    index);

                    if (object == null) {
                        return invalidLegacy();
                    }

                    if (!object.has("timestampMs")
                            || object.isNull("timestampMs")
                            || object.optLong(
                                    "timestampMs",
                                    0L) <= 0L) {
                        return invalidLegacy();
                    }

                    PendingMeasurementStore.Item item =
                            PendingMeasurementStore.Item.fromJson(
                                    object);

                    if (item.id == null
                            || item.id.isBlank()
                            || item.id.length() > 200
                            || item.weightKg <= 0f) {
                        return invalidLegacy();
                    }

                    PendingMeasurementEntity entity =
                            toEntity(
                                    item,
                                    index);

                    PendingMeasurementEntity previous =
                            pendingById.get(
                                    entity.id);

                    if (previous != null) {
                        if (!samePending(
                                previous,
                                entity)) {
                            return invalidLegacy();
                        }

                        continue;
                    }

                    pendingById.put(
                            entity.id,
                            entity);
                }
            }

            pending.addAll(
                    pendingById.values());

            Map<String, PendingMeasurementClaimEntity> claimByKey =
                    new LinkedHashMap<>();

            String claimsEncoded =
                    preferences.getString(
                            CLAIMS_KEY,
                            "");

            if (claimsEncoded != null
                    && !claimsEncoded.isBlank()) {
                JSONArray array =
                        new JSONArray(
                                claimsEncoded);

                for (int index = 0;
                     index < array.length();
                     index++) {
                    JSONObject object =
                            array.optJSONObject(
                                    index);

                    if (object == null) {
                        return invalidLegacy();
                    }

                    PendingMeasurementStore.ClaimResponse response =
                            PendingMeasurementStore.ClaimResponse.fromJson(
                                    object);

                    if (response == null) {
                        return invalidLegacy();
                    }

                    PendingMeasurementClaimEntity entity =
                            new PendingMeasurementClaimEntity(
                                    response.measurementId,
                                    response.peerDeviceId,
                                    encodeIds(
                                            response.profileIds),
                                    response.updatedAtMs,
                                    index);

                    String key =
                            entity.measurementId
                                    + "\n"
                                    + entity.peerDeviceId;

                    PendingMeasurementClaimEntity previous =
                            claimByKey.get(
                                    key);

                    if (previous != null) {
                        if (!previous.profileIdsJson.equals(
                                entity.profileIdsJson)) {
                            return invalidLegacy();
                        }

                        if (entity.updatedAtMs
                                > previous.updatedAtMs) {
                            claimByKey.put(
                                    key,
                                    new PendingMeasurementClaimEntity(
                                            entity.measurementId,
                                            entity.peerDeviceId,
                                            entity.profileIdsJson,
                                            entity.updatedAtMs,
                                            previous.sortOrder));
                        }

                        continue;
                    }

                    claimByKey.put(
                            key,
                            entity);
                }
            }

            claims.addAll(
                    claimByKey.values());

            return new LegacyData(
                    true,
                    pending,
                    claims);
        } catch (JSONException
                 | RuntimeException exception) {
            return invalidLegacy();
        }
    }

    private static LegacyData invalidLegacy() {
        return new LegacyData(
                false,
                new ArrayList<>(),
                new ArrayList<>());
    }

    private static boolean importLegacy(
            PendingMeasurementDao dao,
            LegacyData legacy) {
        for (PendingMeasurementEntity entity :
                legacy.pending) {
            PendingMeasurementEntity existing =
                    dao.findPending(
                            entity.id);

            if (existing == null) {
                long inserted =
                        dao.insertPending(
                                entity);

                if (inserted != -1L) {
                    continue;
                }

                existing =
                        dao.findPending(
                                entity.id);

                if (existing == null) {
                    return false;
                }
            }

            if (!samePending(
                    existing,
                    entity)) {
                return false;
            }
        }

        for (PendingMeasurementClaimEntity entity :
                legacy.claims) {
            PendingMeasurementClaimEntity existing =
                    dao.findClaim(
                            entity.measurementId,
                            entity.peerDeviceId);

            if (existing == null) {
                dao.upsertClaim(
                        entity);
                continue;
            }

            if (!sameClaim(
                    existing,
                    entity)) {
                return false;
            }
        }

        return true;
    }

    private static PendingMeasurementEntity toEntity(
            PendingMeasurementStore.Item item,
            long sortOrder) {
        return new PendingMeasurementEntity(
                item.id,
                item.weightKg,
                item.impedanceHigh,
                item.impedanceLow,
                item.scaleProfileId,
                item.timedOut,
                item.timestampMs,
                item.reason == null
                        ? ""
                        : item.reason,
                item.manualRescue,
                encodeIds(
                        item.candidateProfileIds),
                encodeIds(
                        item.rejectedProfileIds),
                item.selectedProfileId,
                item.selectedOwnerDeviceId,
                sortOrder);
    }

    private static PendingMeasurementStore.Item fromEntity(
            PendingMeasurementEntity entity) {
        PendingMeasurementStore.Item item =
                new PendingMeasurementStore.Item(
                        entity.id,
                        entity.weightKg,
                        entity.impedanceHigh,
                        entity.impedanceLow,
                        entity.scaleProfileId,
                        entity.timedOut,
                        entity.timestampMs,
                        entity.reason,
                        entity.manualRescue,
                        decodeIds(
                                entity.candidateProfileIdsJson),
                        decodeIds(
                                entity.rejectedProfileIdsJson),
                        entity.selectedProfileId,
                        entity.selectedOwnerDeviceId);

        if (item.id.isBlank()
                || item.weightKg <= 0f) {
            throw new IllegalStateException(
                    "Invalid pending Room row");
        }

        return item;
    }

    private static PendingMeasurementStore.ClaimResponse fromEntity(
            PendingMeasurementClaimEntity entity) {
        PendingMeasurementStore.ClaimResponse response =
                new PendingMeasurementStore.ClaimResponse(
                        entity.measurementId,
                        entity.peerDeviceId,
                        decodeIds(
                                entity.profileIdsJson),
                        entity.updatedAtMs);

        if (!response.isValid()) {
            throw new IllegalStateException(
                    "Invalid pending claim Room row");
        }

        return response;
    }

    private static PendingMeasurementStore.Item copyWithDecision(
            PendingMeasurementStore.Item item,
            List<String> rejectedProfileIds,
            String selectedProfileId,
            String selectedOwnerDeviceId) {
        return new PendingMeasurementStore.Item(
                item.id,
                item.weightKg,
                item.impedanceHigh,
                item.impedanceLow,
                item.scaleProfileId,
                item.timedOut,
                item.timestampMs,
                item.reason,
                item.manualRescue,
                item.candidateProfileIds,
                rejectedProfileIds,
                selectedProfileId,
                selectedOwnerDeviceId);
    }

    private static String encodeIds(
            List<String> ids) {
        JSONArray array =
                new JSONArray();

        if (ids != null) {
            for (String id :
                    ids) {
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

        if (encoded == null
                || encoded.isBlank()) {
            return result;
        }

        try {
            JSONArray array =
                    new JSONArray(
                            encoded);

            for (int index = 0;
                 index < array.length();
                 index++) {
                String value =
                        array.optString(
                                index,
                                "");

                if (!value.isBlank()) {
                    result.add(
                            value);
                }
            }

            return result;
        } catch (JSONException exception) {
            throw new IllegalStateException(
                    "Invalid pending profile list",
                    exception);
        }
    }

    private static long nextPendingOrder(
            PendingMeasurementDao dao) {
        return dao.maxPendingSortOrder()
                + 1L;
    }

    private static long nextClaimOrder(
            PendingMeasurementDao dao) {
        return dao.maxClaimSortOrder()
                + 1L;
    }

    private static boolean samePending(
            PendingMeasurementEntity first,
            PendingMeasurementEntity second) {
        return first.id.equals(second.id)
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
                && first.timedOut == second.timedOut
                && first.timestampMs == second.timestampMs
                && first.reason.equals(second.reason)
                && first.manualRescue == second.manualRescue
                && first.candidateProfileIdsJson.equals(
                        second.candidateProfileIdsJson)
                && first.rejectedProfileIdsJson.equals(
                        second.rejectedProfileIdsJson)
                && first.selectedProfileId.equals(
                        second.selectedProfileId)
                && first.selectedOwnerDeviceId.equals(
                        second.selectedOwnerDeviceId);
    }

    private static boolean sameClaim(
            PendingMeasurementClaimEntity first,
            PendingMeasurementClaimEntity second) {
        return first.measurementId.equals(
                        second.measurementId)
                && first.peerDeviceId.equals(
                        second.peerDeviceId)
                && first.profileIdsJson.equals(
                        second.profileIdsJson)
                && first.updatedAtMs
                        == second.updatedAtMs;
    }
}
