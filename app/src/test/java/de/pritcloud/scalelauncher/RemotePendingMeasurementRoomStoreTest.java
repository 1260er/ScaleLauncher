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

public final class RemotePendingMeasurementRoomStoreTest {
    private static final String COLLECTOR_ONE =
            "11111111-1111-4111-8111-111111111111";

    private static final String COLLECTOR_TWO =
            "33333333-3333-4333-8333-333333333333";

    private static final String PROFILE_ONE =
            "22222222-2222-4222-8222-222222222222";

    private static final String PROFILE_TWO =
            "44444444-4444-4444-8444-444444444444";

    @Test
    public void migratesLegacyAndMarksCompletion() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        assertTrue(
                RemotePendingMeasurementStore.upsert(
                        prefs,
                        collector(
                                COLLECTOR_ONE),
                        payload(
                                "legacy",
                                70.0f),
                        List.of(
                                PROFILE_ONE)));

        FakeDao dao =
                new FakeDao();

        assertTrue(
                RemotePendingMeasurementRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertTrue(
                RemotePendingMeasurementRoomStore.isLegacyMigrationMarked(
                        prefs));

        List<RemotePendingMeasurementStore.Item> items =
                RemotePendingMeasurementRoomStore.load(
                        dao);

        assertEquals(
                1,
                items.size());

        assertEquals(
                "legacy",
                items.get(0).measurementId);

        assertEquals(
                COLLECTOR_ONE,
                items.get(0).collectorDeviceId);

        assertEquals(
                List.of(PROFILE_ONE),
                items.get(0).candidateProfileIds);
    }

    @Test
    public void malformedLegacyFailsClosedWithoutMarker() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putString(
                        "items",
                        "not-json")
                .commit();

        FakeDao dao =
                new FakeDao();

        assertFalse(
                RemotePendingMeasurementRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                RemotePendingMeasurementRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertTrue(
                dao.loadAll().isEmpty());
    }

    @Test
    public void invalidLegacyEntryFailsClosedWithoutMarker() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putString(
                        "items",
                        "[{\"measurementId\":\"missing-data\"}]")
                .commit();

        FakeDao dao =
                new FakeDao();

        assertFalse(
                RemotePendingMeasurementRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                RemotePendingMeasurementRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertTrue(
                dao.loadAll().isEmpty());
    }

    @Test
    public void conflictingPreexistingRoomRowFailsClosed() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        assertTrue(
                RemotePendingMeasurementStore.upsert(
                        prefs,
                        collector(
                                COLLECTOR_ONE),
                        payload(
                                "conflict",
                                70.0f),
                        List.of(
                                PROFILE_ONE)));

        RemotePendingMeasurementStore.Item legacy =
                RemotePendingMeasurementStore.load(
                        prefs)
                        .get(0);

        FakeDao dao =
                new FakeDao();

        dao.insert(
                new RemotePendingMeasurementEntity(
                        legacy.measurementId,
                        legacy.collectorDeviceId,
                        legacy.scaleMac,
                        legacy.timestampMs,
                        71.0f,
                        legacy.impedanceHigh,
                        legacy.impedanceLow,
                        legacy.scaleProfileId,
                        "[\"" + PROFILE_ONE + "\"]",
                        legacy.receivedAtMs,
                        0L));

        assertFalse(
                RemotePendingMeasurementRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                RemotePendingMeasurementRoomStore.isLegacyMigrationMarked(
                        prefs));
    }

    @Test
    public void migrationCanRetryAfterPartialImport() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        assertTrue(
                RemotePendingMeasurementStore.upsert(
                        prefs,
                        collector(
                                COLLECTOR_ONE),
                        payload(
                                "retry",
                                70.0f),
                        List.of(
                                PROFILE_ONE)));

        RemotePendingMeasurementStore.Item legacy =
                RemotePendingMeasurementStore.load(
                        prefs)
                        .get(0);

        FakeDao dao =
                new FakeDao();

        dao.insert(
                new RemotePendingMeasurementEntity(
                        legacy.measurementId,
                        legacy.collectorDeviceId,
                        legacy.scaleMac,
                        legacy.timestampMs,
                        legacy.weightKg,
                        legacy.impedanceHigh,
                        legacy.impedanceLow,
                        legacy.scaleProfileId,
                        "[\"" + PROFILE_ONE + "\"]",
                        legacy.receivedAtMs,
                        0L));

        assertTrue(
                RemotePendingMeasurementRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertEquals(
                1,
                dao.loadAll().size());
    }

    @Test
    public void sameCollectorUpdatesExistingMeasurement() {
        FakeDao dao =
                new FakeDao();

        PeerTrustStore.Peer collector =
                collector(
                        COLLECTOR_ONE);

        assertTrue(
                RemotePendingMeasurementRoomStore.upsert(
                        dao,
                        collector,
                        payload(
                                "same-id",
                                70.0f),
                        List.of(
                                PROFILE_ONE)));

        assertTrue(
                RemotePendingMeasurementRoomStore.upsert(
                        dao,
                        collector,
                        payload(
                                "same-id",
                                71.5f),
                        List.of(
                                PROFILE_TWO)));

        List<RemotePendingMeasurementStore.Item> items =
                RemotePendingMeasurementRoomStore.load(
                        dao);

        assertEquals(
                1,
                items.size());

        assertEquals(
                71.5f,
                items.get(0).weightKg,
                0.001f);

        assertEquals(
                List.of(PROFILE_TWO),
                items.get(0).candidateProfileIds);
    }

    @Test
    public void differentCollectorCannotReplaceExistingMeasurement() {
        FakeDao dao =
                new FakeDao();

        assertTrue(
                RemotePendingMeasurementRoomStore.upsert(
                        dao,
                        collector(
                                COLLECTOR_ONE),
                        payload(
                                "shared-id",
                                70.0f),
                        List.of(
                                PROFILE_ONE)));

        assertFalse(
                RemotePendingMeasurementRoomStore.upsert(
                        dao,
                        collector(
                                COLLECTOR_TWO),
                        payload(
                                "shared-id",
                                80.0f),
                        List.of(
                                PROFILE_ONE)));

        RemotePendingMeasurementStore.Item stored =
                RemotePendingMeasurementRoomStore.find(
                        dao,
                        "shared-id");

        assertNotNull(
                stored);

        assertEquals(
                COLLECTOR_ONE,
                stored.collectorDeviceId);

        assertEquals(
                70.0f,
                stored.weightKg,
                0.001f);
    }

    @Test
    public void removeAndRemoveCollectorPreserveOtherRows() {
        FakeDao dao =
                new FakeDao();

        assertTrue(
                RemotePendingMeasurementRoomStore.upsert(
                        dao,
                        collector(
                                COLLECTOR_ONE),
                        payload(
                                "one",
                                70.0f),
                        List.of(
                                PROFILE_ONE)));

        assertTrue(
                RemotePendingMeasurementRoomStore.upsert(
                        dao,
                        collector(
                                COLLECTOR_ONE),
                        payload(
                                "two",
                                71.0f),
                        List.of(
                                PROFILE_ONE)));

        assertTrue(
                RemotePendingMeasurementRoomStore.upsert(
                        dao,
                        collector(
                                COLLECTOR_TWO),
                        payload(
                                "three",
                                72.0f),
                        List.of(
                                PROFILE_ONE)));

        assertTrue(
                RemotePendingMeasurementRoomStore.remove(
                        dao,
                        "one"));

        assertEquals(
                1,
                RemotePendingMeasurementRoomStore.removeCollector(
                        dao,
                        COLLECTOR_ONE));

        List<RemotePendingMeasurementStore.Item> remaining =
                RemotePendingMeasurementRoomStore.load(
                        dao);

        assertEquals(
                1,
                remaining.size());

        assertEquals(
                "three",
                remaining.get(0).measurementId);
    }

    @Test
    public void keepsMeasurementsBeyondFormerLimit() {
        FakeDao dao =
                new FakeDao();

        PeerTrustStore.Peer collector =
                collector(
                        COLLECTOR_ONE);

        for (int index = 0;
             index < 12;
             index++) {
            assertTrue(
                    RemotePendingMeasurementRoomStore.upsert(
                            dao,
                            collector,
                            payload(
                                    "measurement-" + index,
                                    70.0f + index),
                            List.of(
                                    PROFILE_ONE)));
        }

        List<RemotePendingMeasurementStore.Item> items =
                RemotePendingMeasurementRoomStore.load(
                        dao);

        assertEquals(
                12,
                items.size());

        assertEquals(
                "measurement-0",
                items.get(0).measurementId);

        assertEquals(
                "measurement-11",
                items.get(11).measurementId);
    }

    @Test
    public void candidateProfilesRemainSanitizedAndDeduplicated() {
        FakeDao dao =
                new FakeDao();

        assertTrue(
                RemotePendingMeasurementRoomStore.upsert(
                        dao,
                        collector(
                                COLLECTOR_ONE),
                        payload(
                                "profiles",
                                70.0f),
                        List.of(
                                PROFILE_ONE,
                                "ungueltige-profil-id",
                                PROFILE_ONE,
                                PROFILE_TWO)));

        RemotePendingMeasurementStore.Item item =
                RemotePendingMeasurementRoomStore.find(
                        dao,
                        "profiles");

        assertNotNull(
                item);

        assertEquals(
                List.of(
                        PROFILE_ONE,
                        PROFILE_TWO),
                item.candidateProfileIds);
    }

    private static PeerTrustStore.Peer collector(
            String deviceId) {
        return new PeerTrustStore.Peer(
                deviceId,
                "Collector",
                new byte[32]);
    }

    private static PeerMeasurementPayload payload(
            String measurementId,
            float weightKg) {
        return PeerMeasurementPayload.forClaim(
                "04:AE:47:67:4E:07",
                new S400FinalMeasurement(
                        measurementId,
                        weightKg,
                        510.0f,
                        490.0f,
                        1_700_000_000_000L,
                        null),
                List.of(
                        PROFILE_ONE));
    }

    private static final class FakeDao
            implements RemotePendingMeasurementDao {
        private final Map<String, RemotePendingMeasurementEntity> items =
                new LinkedHashMap<>();

        @Override
        public List<RemotePendingMeasurementEntity> loadAll() {
            List<RemotePendingMeasurementEntity> result =
                    new ArrayList<>(
                            items.values());

            result.sort(
                    Comparator.comparingLong(
                            entity ->
                                    entity.sortOrder));

            return result;
        }

        @Override
        public RemotePendingMeasurementEntity find(
                String measurementId) {
            return items.get(
                    measurementId);
        }

        @Override
        public long insert(
                RemotePendingMeasurementEntity entity) {
            if (items.containsKey(
                    entity.measurementId)) {
                return -1L;
            }

            items.put(
                    entity.measurementId,
                    entity);

            return items.size();
        }

        @Override
        public int update(
                RemotePendingMeasurementEntity entity) {
            if (!items.containsKey(
                    entity.measurementId)) {
                return 0;
            }

            items.put(
                    entity.measurementId,
                    entity);

            return 1;
        }

        @Override
        public int delete(
                String measurementId) {
            return items.remove(
                    measurementId) == null
                    ? 0
                    : 1;
        }

        @Override
        public int deleteCollector(
                String collectorDeviceId) {
            int before =
                    items.size();

            items.entrySet()
                    .removeIf(
                            entry ->
                                    collectorDeviceId.equals(
                                            entry.getValue()
                                                    .collectorDeviceId));

            return before
                    - items.size();
        }

        @Override
        public long maxSortOrder() {
            long result =
                    -1L;

            for (RemotePendingMeasurementEntity entity :
                    items.values()) {
                result =
                        Math.max(
                                result,
                                entity.sortOrder);
            }

            return result;
        }
    }
}
