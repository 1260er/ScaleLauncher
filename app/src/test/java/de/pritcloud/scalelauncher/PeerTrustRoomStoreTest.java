package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PeerTrustRoomStoreTest {
    private static final String LOCAL_DEVICE =
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private static final String PEER_ONE =
            "11111111-1111-1111-1111-111111111111";

    private static final String PEER_TWO =
            "22222222-2222-2222-2222-222222222222";

    @Test
    public void migratesEncryptedPeersAndKeepsLegacyData() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putString(
                        "local_device_id",
                        LOCAL_DEVICE)
                .commit();

        List<PeerTrustEntity> legacy =
                List.of(
                        entity(
                                PEER_ONE,
                                "Phone A",
                                "iv-one.cipher-one",
                                0L),
                        entity(
                                PEER_TWO,
                                "Phone B",
                                "iv-two.cipher-two",
                                1L));

        putLegacy(
                prefs,
                legacy);

        String legacyJson =
                prefs.getString(
                        "trusted_peers",
                        "");

        FakeDao dao =
                new FakeDao();

        assertTrue(
                PeerTrustRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertTrue(
                PeerTrustRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                legacyJson,
                prefs.getString(
                        "trusted_peers",
                        ""));

        assertEquals(
                LOCAL_DEVICE,
                prefs.getString(
                        "local_device_id",
                        ""));

        assertEquals(
                2,
                dao.count());

        assertEquals(
                "iv-one.cipher-one",
                dao.find(
                        PEER_ONE)
                        .encryptedSecret);

        assertEquals(
                "iv-two.cipher-two",
                dao.find(
                        PEER_TWO)
                        .encryptedSecret);

        assertEquals(
                0L,
                dao.find(
                        PEER_ONE)
                        .sortOrder);

        assertEquals(
                1L,
                dao.find(
                        PEER_TWO)
                        .sortOrder);
    }

    @Test
    public void malformedLegacyFailsClosedWithoutMarker() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putString(
                        "trusted_peers",
                        "not-json")
                .commit();

        FakeDao dao =
                new FakeDao();

        assertFalse(
                PeerTrustRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                PeerTrustRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                0,
                dao.count());
    }

    @Test
    public void invalidLegacyPeerFailsClosed() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        putLegacy(
                prefs,
                List.of(
                        entity(
                                "broken",
                                "Broken",
                                "iv.cipher",
                                0L)));

        FakeDao dao =
                new FakeDao();

        assertFalse(
                PeerTrustRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                PeerTrustRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                0,
                dao.count());
    }

    @Test
    public void duplicatePeerFailsClosed() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        putLegacy(
                prefs,
                List.of(
                        entity(
                                PEER_ONE,
                                "Phone A",
                                "iv-one.cipher-one",
                                0L),
                        entity(
                                PEER_ONE,
                                "Phone A",
                                "iv-one.cipher-one",
                                1L)));

        FakeDao dao =
                new FakeDao();

        assertFalse(
                PeerTrustRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                PeerTrustRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                0,
                dao.count());
    }

    @Test
    public void conflictingPreexistingRoomPeerFailsClosed() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PeerTrustEntity legacy =
                entity(
                        PEER_ONE,
                        "Phone A",
                        "legacy-iv.legacy-cipher",
                        0L);

        putLegacy(
                prefs,
                List.of(
                        legacy));

        FakeDao dao =
                new FakeDao();

        dao.insert(
                entity(
                        PEER_ONE,
                        "Phone A",
                        "other-iv.other-cipher",
                        0L));

        assertFalse(
                PeerTrustRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                PeerTrustRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                "other-iv.other-cipher",
                dao.find(
                        PEER_ONE)
                        .encryptedSecret);
    }

    @Test
    public void migrationCanRetryExactPartialImport() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PeerTrustEntity first =
                entity(
                        PEER_ONE,
                        "Phone A",
                        "iv-one.cipher-one",
                        0L);

        PeerTrustEntity second =
                entity(
                        PEER_TWO,
                        "Phone B",
                        "iv-two.cipher-two",
                        1L);

        putLegacy(
                prefs,
                List.of(
                        first,
                        second));

        FakeDao dao =
                new FakeDao();

        dao.insert(
                first);

        assertTrue(
                PeerTrustRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertEquals(
                2,
                dao.count());
    }

    @Test
    public void encryptedTrustReplacementPreservesOrder() {
        FakeDao dao =
                new FakeDao();

        PeerTrustRoomStore.trustEncrypted(
                dao,
                PEER_ONE,
                "Phone A",
                "iv-one.cipher-one");

        PeerTrustRoomStore.trustEncrypted(
                dao,
                PEER_TWO,
                "Phone B",
                "iv-two.cipher-two");

        PeerTrustRoomStore.trustEncrypted(
                dao,
                PEER_ONE,
                "Phone A renamed",
                "iv-new.cipher-new");

        assertEquals(
                2,
                dao.count());

        PeerTrustEntity replaced =
                dao.find(
                        PEER_ONE);

        assertEquals(
                "Phone A renamed",
                replaced.label);

        assertEquals(
                "iv-new.cipher-new",
                replaced.encryptedSecret);

        assertEquals(
                0L,
                replaced.sortOrder);

        assertEquals(
                1L,
                dao.find(
                        PEER_TWO)
                        .sortOrder);
    }

    @Test
    public void removeDeletesOnlySelectedPeer() {
        FakeDao dao =
                new FakeDao();

        dao.insert(
                entity(
                        PEER_ONE,
                        "Phone A",
                        "iv-one.cipher-one",
                        0L));

        dao.insert(
                entity(
                        PEER_TWO,
                        "Phone B",
                        "iv-two.cipher-two",
                        1L));

        assertEquals(
                1,
                PeerTrustRoomStore.remove(
                        dao,
                        PEER_ONE));

        assertNull(
                dao.find(
                        PEER_ONE));

        assertEquals(
                PEER_TWO,
                dao.find(
                        PEER_TWO)
                        .deviceId);
    }

    private static PeerTrustEntity entity(
            String deviceId,
            String label,
            String encryptedSecret,
            long sortOrder) {
        return new PeerTrustEntity(
                deviceId,
                label,
                encryptedSecret,
                sortOrder);
    }

    private static void putLegacy(
            InMemorySharedPreferences prefs,
            List<PeerTrustEntity> items) {
        JSONArray array =
                new JSONArray();

        try {
            for (PeerTrustEntity item :
                    items) {
                JSONObject object =
                        new JSONObject();

                object.put(
                        "deviceId",
                        item.deviceId);

                object.put(
                        "label",
                        item.label);

                object.put(
                        "secret",
                        item.encryptedSecret);

                array.put(
                        object);
            }
        } catch (JSONException exception) {
            throw new AssertionError(
                    exception);
        }

        prefs.edit()
                .putString(
                        "trusted_peers",
                        array.toString())
                .commit();
    }

    private static final class FakeDao
            implements PeerTrustDao {
        private final Map<String, PeerTrustEntity> items =
                new LinkedHashMap<>();

        @Override
        public List<PeerTrustEntity> loadAll() {
            List<PeerTrustEntity> result =
                    new ArrayList<>(
                            items.values());

            result.sort(
                    Comparator.comparingLong(
                            item ->
                                    item.sortOrder));

            return result;
        }

        @Override
        public PeerTrustEntity find(
                String deviceId) {
            return items.get(
                    deviceId);
        }

        @Override
        public long insert(
                PeerTrustEntity entity) {
            if (items.containsKey(
                    entity.deviceId)) {
                return -1L;
            }

            items.put(
                    entity.deviceId,
                    entity);

            return 1L;
        }

        @Override
        public int update(
                PeerTrustEntity entity) {
            if (!items.containsKey(
                    entity.deviceId)) {
                return 0;
            }

            items.put(
                    entity.deviceId,
                    entity);

            return 1;
        }

        @Override
        public int delete(
                String deviceId) {
            return items.remove(
                    deviceId) == null
                    ? 0
                    : 1;
        }

        @Override
        public int deleteAll() {
            int count =
                    items.size();

            items.clear();

            return count;
        }

        @Override
        public int count() {
            return items.size();
        }

        @Override
        public Long maxSortOrder() {
            Long maximum =
                    null;

            for (PeerTrustEntity entity :
                    items.values()) {
                if (maximum == null
                        || entity.sortOrder > maximum) {
                    maximum =
                            entity.sortOrder;
                }
            }

            return maximum;
        }
    }
}
