package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PendingMeasurementRoomStoreTest {
    @Test
    public void migratesPendingAndClaimsAndMarksCompletion() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        String profileId =
                "11111111-1111-4111-8111-111111111111";

        String peerId =
                "22222222-2222-4222-8222-222222222222";

        PendingMeasurementStore.add(
                prefs,
                new S400FinalMeasurement(
                        "legacy-measurement",
                        70.0f,
                        510.0f,
                        490.0f,
                        1_700_000_000_000L,
                        null),
                "legacy",
                List.of(profileId));

        PendingMeasurementStore.recordClaimResponse(
                prefs,
                "legacy-measurement",
                peerId,
                List.of(profileId));

        FakeDao dao =
                new FakeDao();

        assertTrue(
                PendingMeasurementRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertTrue(
                PendingMeasurementRoomStore.isLegacyMigrationMarked(
                        prefs));

        List<PendingMeasurementStore.Item> pending =
                PendingMeasurementRoomStore.load(
                        dao);

        assertEquals(
                1,
                pending.size());

        assertEquals(
                "legacy-measurement",
                pending.get(0).id);

        List<PendingMeasurementStore.ClaimResponse> claims =
                PendingMeasurementRoomStore.claimResponses(
                        dao,
                        "legacy-measurement");

        assertEquals(
                1,
                claims.size());

        assertEquals(
                peerId,
                claims.get(0).peerDeviceId);
    }

    @Test
    public void legacyWithoutTimestampFailsClosed() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putString(
                        "pending_measurements_json",
                        "[{\"id\":\"missing-timestamp\","
                                + "\"weightKg\":70.0,"
                                + "\"impedanceHigh\":510.0,"
                                + "\"timedOut\":false,"
                                + "\"reason\":\"test\","
                                + "\"manualRescue\":false}]")
                .commit();

        FakeDao dao =
                new FakeDao();

        assertFalse(
                PendingMeasurementRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                PendingMeasurementRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertTrue(
                dao.loadPending().isEmpty());
    }

    @Test
    public void malformedLegacyFailsClosedWithoutMarker() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putString(
                        "pending_measurements_json",
                        "not-json")
                .commit();

        FakeDao dao =
                new FakeDao();

        assertFalse(
                PendingMeasurementRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                PendingMeasurementRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertTrue(
                dao.loadPending().isEmpty());
    }

    @Test
    public void conflictingPreexistingRoomRowFailsClosed() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PendingMeasurementStore.add(
                prefs,
                new S400FinalMeasurement(
                        "conflict",
                        70.0f,
                        510.0f,
                        490.0f,
                        1_700_000_000_000L,
                        null),
                "legacy");

        FakeDao dao =
                new FakeDao();

        dao.insertPending(
                new PendingMeasurementEntity(
                        "conflict",
                        71.0f,
                        510.0f,
                        490.0f,
                        null,
                        false,
                        1_700_000_000_000L,
                        "legacy",
                        false,
                        "[]",
                        "[]",
                        "",
                        "",
                        0L));

        assertFalse(
                PendingMeasurementRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                PendingMeasurementRoomStore.isLegacyMigrationMarked(
                        prefs));
    }

    @Test
    public void migrationCanRetryAfterPartialImport() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PendingMeasurementStore.add(
                prefs,
                new S400FinalMeasurement(
                        "retry",
                        70.0f,
                        510.0f,
                        490.0f,
                        1_700_000_000_000L,
                        null),
                "legacy");

        FakeDao dao =
                new FakeDao();

        PendingMeasurementStore.Item legacy =
                PendingMeasurementStore.find(
                        prefs,
                        "retry");

        dao.insertPending(
                new PendingMeasurementEntity(
                        legacy.id,
                        legacy.weightKg,
                        legacy.impedanceHigh,
                        legacy.impedanceLow,
                        legacy.scaleProfileId,
                        legacy.timedOut,
                        legacy.timestampMs,
                        legacy.reason,
                        legacy.manualRescue,
                        "[]",
                        "[]",
                        "",
                        "",
                        0L));

        assertTrue(
                PendingMeasurementRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertEquals(
                1,
                dao.loadPending().size());
    }

    @Test
    public void roomBackendPreservesDecisionAndClaimSemantics() {
        FakeDao dao =
                new FakeDao();

        String firstProfile =
                "11111111-1111-4111-8111-111111111111";

        String secondProfile =
                "22222222-2222-4222-8222-222222222222";

        String owner =
                "33333333-3333-4333-8333-333333333333";

        String peer =
                "44444444-4444-4444-8444-444444444444";

        PendingMeasurementStore.Item pending =
                PendingMeasurementRoomStore.add(
                        dao,
                        new S400FinalMeasurement(
                                "room-pending",
                                70.0f,
                                510.0f,
                                490.0f,
                                1_700_000_000_000L,
                                null),
                        "test",
                        List.of(
                                firstProfile,
                                secondProfile),
                        false);

        assertTrue(
                PendingMeasurementRoomStore.rejectCandidate(
                        dao,
                        pending.id,
                        firstProfile));

        assertTrue(
                PendingMeasurementRoomStore.selectCandidate(
                        dao,
                        pending.id,
                        secondProfile,
                        owner));

        PendingMeasurementStore.Item selected =
                PendingMeasurementRoomStore.find(
                        dao,
                        pending.id);

        assertNotNull(
                selected);

        assertTrue(
                selected.isResolved());

        assertEquals(
                secondProfile,
                selected.selectedProfileId);

        PendingMeasurementRoomStore.recordClaimResponse(
                dao,
                pending.id,
                peer,
                List.of(secondProfile));

        PendingMeasurementRoomStore.recordClaimResponse(
                dao,
                pending.id,
                peer,
                List.of(firstProfile));

        List<PendingMeasurementStore.ClaimResponse> claims =
                PendingMeasurementRoomStore.claimResponses(
                        dao,
                        pending.id);

        assertEquals(
                1,
                claims.size());

        assertEquals(
                List.of(firstProfile),
                claims.get(0).profileIds);

        PendingMeasurementRoomStore.remove(
                dao,
                pending.id);

        assertTrue(
                PendingMeasurementRoomStore.load(
                        dao).isEmpty());

        assertTrue(
                PendingMeasurementRoomStore.claimResponses(
                        dao,
                        pending.id).isEmpty());
    }

    @Test
    public void roomBackendKeepsMeasurementsBeyondFormerLimit() {
        FakeDao dao = new FakeDao();

        for (int index = 0; index < 12; index++) {
            PendingMeasurementRoomStore.add(
                    dao,
                    new S400FinalMeasurement(
                            "measurement-" + index,
                            70.0f + index,
                            510.0f,
                            490.0f,
                            1_700_000_000_000L + index,
                            null),
                    "test",
                    List.of(),
                    false);
        }

        List<PendingMeasurementStore.Item> items =
                PendingMeasurementRoomStore.load(dao);

        assertEquals(12, items.size());
        assertEquals("measurement-0", items.get(0).id);
        assertEquals("measurement-11", items.get(11).id);
    }

    @Test
    public void updatedClaimMovesToLatestPosition() {
        FakeDao dao = new FakeDao();

        String measurementId = "claim-order";
        String firstPeer =
                "11111111-1111-4111-8111-111111111111";
        String secondPeer =
                "22222222-2222-4222-8222-222222222222";

        PendingMeasurementRoomStore.recordClaimResponse(
                dao,
                measurementId,
                firstPeer,
                List.of());

        PendingMeasurementRoomStore.recordClaimResponse(
                dao,
                measurementId,
                secondPeer,
                List.of());

        PendingMeasurementRoomStore.recordClaimResponse(
                dao,
                measurementId,
                firstPeer,
                List.of());

        List<PendingMeasurementStore.ClaimResponse> responses =
                PendingMeasurementRoomStore.claimResponses(
                        dao,
                        measurementId);

        assertEquals(2, responses.size());
        assertEquals(secondPeer, responses.get(0).peerDeviceId);
        assertEquals(firstPeer, responses.get(1).peerDeviceId);
    }

    private static final class FakeDao
            implements PendingMeasurementDao {
        private final Map<String, PendingMeasurementEntity> pending =
                new LinkedHashMap<>();

        private final Map<String, PendingMeasurementClaimEntity> claims =
                new LinkedHashMap<>();

        @Override
        public List<PendingMeasurementEntity> loadPending() {
            List<PendingMeasurementEntity> result =
                    new ArrayList<>(
                            pending.values());

            result.sort(
                    Comparator.comparingLong(
                            entity ->
                                    entity.sortOrder));

            return result;
        }

        @Override
        public PendingMeasurementEntity findPending(
                String id) {
            return pending.get(
                    id);
        }

        @Override
        public long insertPending(
                PendingMeasurementEntity entity) {
            if (pending.containsKey(
                    entity.id)) {
                return -1L;
            }

            pending.put(
                    entity.id,
                    entity);

            return pending.size();
        }

        @Override
        public int updatePending(
                PendingMeasurementEntity entity) {
            if (!pending.containsKey(
                    entity.id)) {
                return 0;
            }

            pending.put(
                    entity.id,
                    entity);

            return 1;
        }

        @Override
        public int deletePending(
                String id) {
            return pending.remove(
                    id) == null
                    ? 0
                    : 1;
        }

        @Override
        public long maxPendingSortOrder() {
            long result =
                    -1L;

            for (PendingMeasurementEntity entity :
                    pending.values()) {
                result =
                        Math.max(
                                result,
                                entity.sortOrder);
            }

            return result;
        }

        @Override
        public List<PendingMeasurementClaimEntity> loadClaims(
                String measurementId) {
            List<PendingMeasurementClaimEntity> result =
                    new ArrayList<>();

            for (PendingMeasurementClaimEntity entity :
                    claims.values()) {
                if (measurementId.equals(
                        entity.measurementId)) {
                    result.add(
                            entity);
                }
            }

            result.sort(
                    Comparator.comparingLong(
                            entity ->
                                    entity.sortOrder));

            return result;
        }

        @Override
        public PendingMeasurementClaimEntity findClaim(
                String measurementId,
                String peerDeviceId) {
            return claims.get(
                    key(
                            measurementId,
                            peerDeviceId));
        }

        @Override
        public long upsertClaim(
                PendingMeasurementClaimEntity entity) {
            claims.put(
                    key(
                            entity.measurementId,
                            entity.peerDeviceId),
                    entity);

            return claims.size();
        }

        @Override
        public int deleteClaimsForMeasurement(
                String measurementId) {
            int before =
                    claims.size();

            claims.entrySet()
                    .removeIf(
                            entry ->
                                    measurementId.equals(
                                            entry.getValue()
                                                    .measurementId));

            return before
                    - claims.size();
        }

        @Override
        public int deleteClaimsForPeer(
                String peerDeviceId) {
            int before =
                    claims.size();

            claims.entrySet()
                    .removeIf(
                            entry ->
                                    peerDeviceId.equals(
                                            entry.getValue()
                                                    .peerDeviceId));

            return before
                    - claims.size();
        }

        @Override
        public long maxClaimSortOrder() {
            long result =
                    -1L;

            for (PendingMeasurementClaimEntity entity :
                    claims.values()) {
                result =
                        Math.max(
                                result,
                                entity.sortOrder);
            }

            return result;
        }

        private static String key(
                String measurementId,
                String peerDeviceId) {
            return measurementId
                    + "\n"
                    + peerDeviceId;
        }
    }
}
