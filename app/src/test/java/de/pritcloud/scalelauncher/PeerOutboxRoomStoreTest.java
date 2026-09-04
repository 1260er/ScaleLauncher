package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PeerOutboxRoomStoreTest {
    private static final String PEER_ONE =
            "11111111-1111-1111-1111-111111111111";

    private static final String PEER_TWO =
            "22222222-2222-2222-2222-222222222222";

    @Test
    public void migratesLegacyAndKeepsLegacyJson() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        List<PeerOutboxStore.Item> legacy =
                List.of(
                        item(
                                "one",
                                PEER_ONE,
                                "measurement-1",
                                "payload-1",
                                1L),
                        item(
                                "two",
                                PEER_TWO,
                                "measurement-2",
                                "payload-2",
                                2L));

        PeerOutboxStore.save(
                prefs,
                legacy);

        String legacyJson =
                prefs.getString(
                        "items",
                        "");

        FakeDao dao =
                new FakeDao();

        assertTrue(
                PeerOutboxRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertTrue(
                PeerOutboxRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                legacyJson,
                prefs.getString(
                        "items",
                        ""));

        List<PeerOutboxStore.Item> stored =
                PeerOutboxRoomStore.load(
                        dao);

        assertEquals(
                2,
                stored.size());
        assertEquals(
                "one",
                stored.get(0).messageId);
        assertEquals(
                "two",
                stored.get(1).messageId);
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
                PeerOutboxRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                PeerOutboxRoomStore.isLegacyMigrationMarked(
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
                        "[{\"messageId\":\"broken\"}]")
                .commit();

        FakeDao dao =
                new FakeDao();

        assertFalse(
                PeerOutboxRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                PeerOutboxRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertTrue(
                dao.loadAll().isEmpty());
    }

    @Test
    public void conflictingPreexistingRoomRowFailsClosed() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PeerOutboxStore.save(
                prefs,
                List.of(
                        item(
                                "message",
                                PEER_ONE,
                                "measurement-1",
                                "legacy",
                                1L)));

        FakeDao dao =
                new FakeDao();

        dao.insert(
                new PeerOutboxEntity(
                        PEER_ONE,
                        "message",
                        PeerOutboxStore.KIND_MEASUREMENT,
                        "measurement-1",
                        "different",
                        1L,
                        0L));

        assertFalse(
                PeerOutboxRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                PeerOutboxRoomStore.isLegacyMigrationMarked(
                        prefs));
    }

    @Test
    public void migrationCanRetryAfterPartialImport() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PeerOutboxStore.Item legacy =
                item(
                        "message",
                        PEER_ONE,
                        "measurement-1",
                        "payload",
                        1L);

        PeerOutboxStore.save(
                prefs,
                List.of(legacy));

        FakeDao dao =
                new FakeDao();

        dao.insert(
                new PeerOutboxEntity(
                        legacy.peerDeviceId,
                        legacy.messageId,
                        legacy.kind,
                        legacy.dedupKey,
                        legacy.payload,
                        legacy.createdAtMs,
                        0L));

        assertTrue(
                PeerOutboxRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertEquals(
                1,
                dao.loadAll().size());
    }

    @Test
    public void sameMessageIdIsTrackedSeparatelyPerPeer() {
        FakeDao dao =
                new FakeDao();

        PeerOutboxRoomStore.enqueue(
                dao,
                item(
                        "shared",
                        PEER_ONE,
                        "measurement-1",
                        "one",
                        1L),
                false);

        PeerOutboxRoomStore.enqueue(
                dao,
                item(
                        "shared",
                        PEER_TWO,
                        "measurement-2",
                        "two",
                        2L),
                false);

        List<PeerOutboxStore.Item> stored =
                PeerOutboxRoomStore.load(
                        dao);

        assertEquals(
                2,
                stored.size());
        assertEquals(
                PEER_ONE,
                stored.get(0).peerDeviceId);
        assertEquals(
                PEER_TWO,
                stored.get(1).peerDeviceId);
    }

    @Test
    public void repeatedMessageIdForSamePeerKeepsFirstItem() {
        FakeDao dao =
                new FakeDao();

        PeerOutboxRoomStore.enqueue(
                dao,
                item(
                        "same",
                        PEER_ONE,
                        "measurement-1",
                        "first",
                        1L),
                false);

        PeerOutboxRoomStore.enqueue(
                dao,
                item(
                        "same",
                        PEER_ONE,
                        "measurement-2",
                        "second",
                        2L),
                false);

        List<PeerOutboxStore.Item> stored =
                PeerOutboxRoomStore.load(
                        dao);

        assertEquals(
                1,
                stored.size());
        assertEquals(
                "measurement-1",
                stored.get(0).dedupKey);
        assertEquals(
                "first",
                stored.get(0).payload);
    }

    @Test
    public void coalesceReplacesMatchingItemAtEnd() {
        FakeDao dao =
                new FakeDao();

        PeerOutboxRoomStore.enqueue(
                dao,
                item(
                        "old",
                        PEER_ONE,
                        "measurement-1",
                        "old-payload",
                        1L),
                true);

        PeerOutboxRoomStore.enqueue(
                dao,
                item(
                        "other",
                        PEER_ONE,
                        "measurement-2",
                        "other-payload",
                        2L),
                true);

        PeerOutboxRoomStore.enqueue(
                dao,
                item(
                        "new",
                        PEER_ONE,
                        "measurement-1",
                        "new-payload",
                        3L),
                true);

        List<PeerOutboxStore.Item> stored =
                PeerOutboxRoomStore.load(
                        dao);

        assertEquals(
                2,
                stored.size());
        assertEquals(
                "other",
                stored.get(0).messageId);
        assertEquals(
                "new",
                stored.get(1).messageId);
        assertEquals(
                "new-payload",
                stored.get(1).payload);
    }

    @Test
    public void removeMeasurementDoesNotRemoveSimilarId() {
        FakeDao dao =
                new FakeDao();

        PeerOutboxRoomStore.enqueue(
                dao,
                item(
                        "measurement",
                        PEER_ONE,
                        "measurement-1",
                        "one",
                        1L),
                false);

        PeerOutboxRoomStore.enqueue(
                dao,
                new PeerOutboxStore.Item(
                        "decision",
                        PEER_ONE,
                        PeerOutboxStore.KIND_DECISION,
                        "measurement-1:profile",
                        "decision-payload",
                        2L),
                false);

        PeerOutboxRoomStore.enqueue(
                dao,
                item(
                        "similar",
                        PEER_ONE,
                        "measurement-10",
                        "ten",
                        3L),
                false);

        assertEquals(
                2,
                PeerOutboxRoomStore.removeMeasurement(
                        dao,
                        "measurement-1"));

        List<PeerOutboxStore.Item> remaining =
                PeerOutboxRoomStore.load(
                        dao);

        assertEquals(
                1,
                remaining.size());
        assertEquals(
                "measurement-10",
                remaining.get(0).dedupKey);
    }

    @Test
    public void removePeerPreservesOtherPeer() {
        FakeDao dao =
                new FakeDao();

        PeerOutboxRoomStore.enqueue(
                dao,
                item(
                        "one",
                        PEER_ONE,
                        "measurement-1",
                        "one",
                        1L),
                false);

        PeerOutboxRoomStore.enqueue(
                dao,
                item(
                        "two",
                        PEER_TWO,
                        "measurement-2",
                        "two",
                        2L),
                false);

        assertEquals(
                1,
                PeerOutboxRoomStore.removePeer(
                        dao,
                        PEER_ONE));

        List<PeerOutboxStore.Item> remaining =
                PeerOutboxRoomStore.load(
                        dao);

        assertEquals(
                1,
                remaining.size());
        assertEquals(
                PEER_TWO,
                remaining.get(0).peerDeviceId);
    }

    @Test
    public void keepsItemsBeyondFormerThousandItemLimit() {
        FakeDao dao =
                new FakeDao();

        for (int index = 0;
             index < 1001;
             index++) {
            PeerOutboxRoomStore.enqueue(
                    dao,
                    item(
                            "message-" + index,
                            PEER_ONE,
                            "measurement-" + index,
                            "payload-" + index,
                            1_000L + index),
                    false);
        }

        List<PeerOutboxStore.Item> stored =
                PeerOutboxRoomStore.load(
                        dao);

        assertEquals(
                1001,
                stored.size());
        assertEquals(
                "message-0",
                stored.get(0).messageId);
        assertEquals(
                "message-1000",
                stored.get(1000).messageId);
    }

    private static PeerOutboxStore.Item item(
            String messageId,
            String peerDeviceId,
            String dedupKey,
            String payload,
            long createdAtMs) {
        return new PeerOutboxStore.Item(
                messageId,
                peerDeviceId,
                PeerOutboxStore.KIND_MEASUREMENT,
                dedupKey,
                payload,
                createdAtMs);
    }

    private static final class FakeDao
            implements PeerOutboxDao {
        private final Map<String, PeerOutboxEntity> items =
                new LinkedHashMap<>();

        private static String key(
                String peerDeviceId,
                String messageId) {
            return peerDeviceId
                    + "\u0000"
                    + messageId;
        }

        @Override
        public List<PeerOutboxEntity> loadAll() {
            List<PeerOutboxEntity> result =
                    new ArrayList<>(
                            items.values());

            result.sort(
                    Comparator.comparingLong(
                            entity ->
                                    entity.sortOrder));

            return result;
        }

        @Override
        public List<PeerOutboxEntity> loadForPeer(
                String peerDeviceId) {
            List<PeerOutboxEntity> result =
                    new ArrayList<>();

            for (PeerOutboxEntity entity :
                    items.values()) {
                if (peerDeviceId.equals(
                        entity.peerDeviceId)) {
                    result.add(entity);
                }
            }

            result.sort(
                    Comparator.comparingLong(
                            entity ->
                                    entity.sortOrder));

            return result;
        }

        @Override
        public PeerOutboxEntity find(
                String peerDeviceId,
                String messageId) {
            return items.get(
                    key(
                            peerDeviceId,
                            messageId));
        }

        @Override
        public long insert(
                PeerOutboxEntity entity) {
            String key =
                    key(
                            entity.peerDeviceId,
                            entity.messageId);

            if (items.containsKey(key)) {
                return -1L;
            }

            items.put(
                    key,
                    entity);

            return items.size();
        }

        @Override
        public int delete(
                String peerDeviceId,
                String messageId) {
            return items.remove(
                    key(
                            peerDeviceId,
                            messageId)) == null
                    ? 0
                    : 1;
        }

        @Override
        public int deletePeer(
                String peerDeviceId) {
            int before =
                    items.size();

            items.entrySet()
                    .removeIf(
                            entry ->
                                    peerDeviceId.equals(
                                            entry.getValue()
                                                    .peerDeviceId));

            return before
                    - items.size();
        }

        @Override
        public int deleteCoalesced(
                String peerDeviceId,
                String kind,
                String dedupKey) {
            int before =
                    items.size();

            items.entrySet()
                    .removeIf(
                            entry -> {
                                PeerOutboxEntity entity =
                                        entry.getValue();

                                return peerDeviceId.equals(
                                                entity.peerDeviceId)
                                        && kind.equals(
                                                entity.kind)
                                        && dedupKey.equals(
                                                entity.dedupKey);
                            });

            return before
                    - items.size();
        }

        @Override
        public int deleteMeasurement(
                String measurementId,
                String measurementPrefix) {
            int before =
                    items.size();

            items.entrySet()
                    .removeIf(
                            entry -> {
                                String dedupKey =
                                        entry.getValue()
                                                .dedupKey;

                                return measurementId.equals(
                                                dedupKey)
                                        || dedupKey.startsWith(
                                                measurementPrefix);
                            });

            return before
                    - items.size();
        }

        @Override
        public int count() {
            return items.size();
        }

        @Override
        public long maxSortOrder() {
            long result =
                    -1L;

            for (PeerOutboxEntity entity :
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
