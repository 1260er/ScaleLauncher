package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PeerInboxDedupRoomStoreTest {
    private static final String PEER_ONE =
            "11111111-1111-1111-1111-111111111111";

    private static final String PEER_TWO =
            "22222222-2222-2222-2222-222222222222";

    @Test
    public void migratesLegacyAndKeepsLegacyJson() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        putLegacy(
                prefs,
                List.of(
                        entity(
                                PEER_ONE,
                                "one",
                                10L),
                        entity(
                                PEER_TWO,
                                "two",
                                20L)));

        String legacyJson =
                prefs.getString(
                        "processed",
                        "");

        FakeDao dao =
                new FakeDao();

        assertTrue(
                PeerInboxDedupRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertTrue(
                PeerInboxDedupRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                legacyJson,
                prefs.getString(
                        "processed",
                        ""));

        assertEquals(
                2,
                dao.count());

        assertEquals(
                10L,
                dao.find(
                        PEER_ONE,
                        "one")
                        .seenAtMs);

        assertEquals(
                20L,
                dao.find(
                        PEER_TWO,
                        "two")
                        .seenAtMs);
    }

    @Test
    public void malformedLegacyFailsClosedWithoutMarker() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putString(
                        "processed",
                        "not-json")
                .commit();

        FakeDao dao =
                new FakeDao();

        assertFalse(
                PeerInboxDedupRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                PeerInboxDedupRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                0,
                dao.count());
    }

    @Test
    public void invalidLegacyEntryFailsClosedWithoutMarker() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putString(
                        "processed",
                        "[{\"senderDeviceId\":\"broken\"}]")
                .commit();

        FakeDao dao =
                new FakeDao();

        assertFalse(
                PeerInboxDedupRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                PeerInboxDedupRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                0,
                dao.count());
    }

    @Test
    public void conflictingDuplicateLegacyEntryFailsClosed() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        putLegacy(
                prefs,
                List.of(
                        entity(
                                PEER_ONE,
                                "duplicate",
                                10L),
                        entity(
                                PEER_ONE,
                                "duplicate",
                                20L)));

        FakeDao dao =
                new FakeDao();

        assertFalse(
                PeerInboxDedupRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                PeerInboxDedupRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                0,
                dao.count());
    }

    @Test
    public void conflictingPreexistingRoomRowFailsClosed() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        putLegacy(
                prefs,
                List.of(
                        entity(
                                PEER_ONE,
                                "message",
                                10L)));

        FakeDao dao =
                new FakeDao();

        dao.insert(
                entity(
                        PEER_ONE,
                        "message",
                        99L));

        assertFalse(
                PeerInboxDedupRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                PeerInboxDedupRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                99L,
                dao.find(
                        PEER_ONE,
                        "message")
                        .seenAtMs);
    }

    @Test
    public void migrationCanRetryAfterExactPartialImport() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PeerInboxDedupEntity legacy =
                entity(
                        PEER_ONE,
                        "message",
                        10L);

        putLegacy(
                prefs,
                List.of(
                        legacy));

        FakeDao dao =
                new FakeDao();

        dao.insert(
                legacy);

        assertTrue(
                PeerInboxDedupRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertEquals(
                1,
                dao.count());
    }

    @Test
    public void sameMessageIdIsTrackedSeparatelyPerPeer() {
        FakeDao dao =
                new FakeDao();

        assertTrue(
                PeerInboxDedupRoomStore.mark(
                        dao,
                        PEER_ONE,
                        "shared-message",
                        10L));

        assertTrue(
                PeerInboxDedupRoomStore.mark(
                        dao,
                        PEER_TWO,
                        "shared-message",
                        20L));

        assertTrue(
                PeerInboxDedupRoomStore.contains(
                        dao,
                        PEER_ONE,
                        "shared-message"));

        assertTrue(
                PeerInboxDedupRoomStore.contains(
                        dao,
                        PEER_TWO,
                        "shared-message"));

        assertEquals(
                2,
                dao.count());
    }

    @Test
    public void repeatedMessageUpdatesTimestampWithoutDuplicate() {
        FakeDao dao =
                new FakeDao();

        PeerInboxDedupRoomStore.mark(
                dao,
                PEER_ONE,
                "repeated-message",
                10L);

        PeerInboxDedupRoomStore.mark(
                dao,
                PEER_ONE,
                "repeated-message",
                20L);

        assertEquals(
                1,
                dao.count());

        assertEquals(
                20L,
                dao.find(
                        PEER_ONE,
                        "repeated-message")
                        .seenAtMs);
    }

    @Test
    public void removePeerRemovesOnlyItsItems() {
        FakeDao dao =
                new FakeDao();

        PeerInboxDedupRoomStore.mark(
                dao,
                PEER_ONE,
                "one",
                10L);

        PeerInboxDedupRoomStore.mark(
                dao,
                PEER_ONE,
                "two",
                20L);

        PeerInboxDedupRoomStore.mark(
                dao,
                PEER_TWO,
                "other",
                30L);

        assertEquals(
                2,
                PeerInboxDedupRoomStore.removePeer(
                        dao,
                        PEER_ONE));

        assertFalse(
                PeerInboxDedupRoomStore.contains(
                        dao,
                        PEER_ONE,
                        "one"));

        assertTrue(
                PeerInboxDedupRoomStore.contains(
                        dao,
                        PEER_TWO,
                        "other"));
    }

    @Test
    public void keepsItemsBeyondFormerThousandItemLimit() {
        FakeDao dao =
                new FakeDao();

        for (int index = 0;
             index <= 1000;
             index++) {
            PeerInboxDedupRoomStore.mark(
                    dao,
                    PEER_ONE,
                    "message-" + index,
                    index + 1L);
        }

        assertEquals(
                1001,
                dao.count());

        assertTrue(
                PeerInboxDedupRoomStore.contains(
                        dao,
                        PEER_ONE,
                        "message-0"));

        assertTrue(
                PeerInboxDedupRoomStore.contains(
                        dao,
                        PEER_ONE,
                        "message-1000"));
    }

    private static PeerInboxDedupEntity entity(
            String senderDeviceId,
            String messageId,
            long seenAtMs) {
        return new PeerInboxDedupEntity(
                senderDeviceId,
                messageId,
                seenAtMs);
    }

    private static void putLegacy(
            InMemorySharedPreferences prefs,
            List<PeerInboxDedupEntity> items) {
        JSONArray array =
                new JSONArray();

        try {
            for (PeerInboxDedupEntity item :
                    items) {
                JSONObject object =
                        new JSONObject();

                object.put(
                        "senderDeviceId",
                        item.senderDeviceId);

                object.put(
                        "messageId",
                        item.messageId);

                object.put(
                        "seenAtMs",
                        item.seenAtMs);

                array.put(
                        object);
            }
        } catch (JSONException exception) {
            throw new AssertionError(
                    exception);
        }

        prefs.edit()
                .putString(
                        "processed",
                        array.toString())
                .commit();
    }

    private static final class FakeDao
            implements PeerInboxDedupDao {
        private final Map<String, PeerInboxDedupEntity> items =
                new LinkedHashMap<>();

        @Override
        public PeerInboxDedupEntity find(
                String senderDeviceId,
                String messageId) {
            return items.get(
                    key(
                            senderDeviceId,
                            messageId));
        }

        @Override
        public boolean contains(
                String senderDeviceId,
                String messageId) {
            return items.containsKey(
                    key(
                            senderDeviceId,
                            messageId));
        }

        @Override
        public long upsert(
                PeerInboxDedupEntity entity) {
            items.put(
                    key(
                            entity.senderDeviceId,
                            entity.messageId),
                    entity);

            return 1L;
        }

        @Override
        public long insert(
                PeerInboxDedupEntity entity) {
            String key =
                    key(
                            entity.senderDeviceId,
                            entity.messageId);

            if (items.containsKey(
                    key)) {
                return -1L;
            }

            items.put(
                    key,
                    entity);

            return 1L;
        }

        @Override
        public int deletePeer(
                String peerDeviceId) {
            int before =
                    items.size();

            Iterator<Map.Entry<String, PeerInboxDedupEntity>> iterator =
                    items.entrySet()
                            .iterator();

            while (iterator.hasNext()) {
                PeerInboxDedupEntity entity =
                        iterator.next()
                                .getValue();

                if (entity.senderDeviceId.equals(
                        peerDeviceId)) {
                    iterator.remove();
                }
            }

            return before
                    - items.size();
        }

        @Override
        public int count() {
            return items.size();
        }

        private static String key(
                String senderDeviceId,
                String messageId) {
            return senderDeviceId
                    + "|"
                    + messageId;
        }
    }
}
