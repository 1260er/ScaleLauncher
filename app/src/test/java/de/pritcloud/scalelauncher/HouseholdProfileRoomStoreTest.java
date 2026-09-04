package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HouseholdProfileRoomStoreTest {
    private static final String PROFILE_ONE =
            "22222222-2222-4222-8222-222222222222";

    private static final String PROFILE_TWO =
            "44444444-4444-4444-8444-444444444444";

    private static final String PROFILE_THREE =
            "55555555-5555-4555-8555-555555555555";

    private static final String OWNER_ONE =
            "11111111-1111-4111-8111-111111111111";

    private static final String OWNER_TWO =
            "33333333-3333-4333-8333-333333333333";

    @Test
    public void migratesLegacyAndKeepsOrderAndJson() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        List<HouseholdProfile> legacy =
                List.of(
                        profile(
                                PROFILE_ONE,
                                OWNER_ONE,
                                1L,
                                70.0f),
                        profile(
                                PROFILE_TWO,
                                OWNER_TWO,
                                2L,
                                80.0f));

        putLegacy(
                prefs,
                legacy);

        String legacyJson =
                prefs.getString(
                        "profiles",
                        "");

        FakeDao dao =
                new FakeDao();

        assertTrue(
                HouseholdProfileRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertTrue(
                HouseholdProfileRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                legacyJson,
                prefs.getString(
                        "profiles",
                        ""));

        List<HouseholdProfile> stored =
                HouseholdProfileRoomStore.load(
                        dao);

        assertEquals(
                2,
                stored.size());

        assertEquals(
                PROFILE_ONE,
                stored.get(0).profileId);

        assertEquals(
                PROFILE_TWO,
                stored.get(1).profileId);
    }

    @Test
    public void malformedLegacyFailsClosedWithoutMarker() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putString(
                        "profiles",
                        "not-json")
                .commit();

        FakeDao dao =
                new FakeDao();

        assertFalse(
                HouseholdProfileRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                HouseholdProfileRoomStore.isLegacyMigrationMarked(
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
                        "profiles",
                        "[{}]")
                .commit();

        FakeDao dao =
                new FakeDao();

        assertFalse(
                HouseholdProfileRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                HouseholdProfileRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                0,
                dao.count());
    }

    @Test
    public void duplicateLegacyProfileIdFailsClosed() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        putLegacy(
                prefs,
                List.of(
                        profile(
                                PROFILE_ONE,
                                OWNER_ONE,
                                1L,
                                70.0f),
                        profile(
                                PROFILE_ONE,
                                OWNER_ONE,
                                1L,
                                70.0f)));

        FakeDao dao =
                new FakeDao();

        assertFalse(
                HouseholdProfileRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                HouseholdProfileRoomStore.isLegacyMigrationMarked(
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
                        profile(
                                PROFILE_ONE,
                                OWNER_ONE,
                                1L,
                                70.0f)));

        FakeDao dao =
                new FakeDao();

        dao.insert(
                entity(
                        profile(
                                PROFILE_ONE,
                                OWNER_ONE,
                                2L,
                                71.0f),
                        0L));

        assertFalse(
                HouseholdProfileRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                HouseholdProfileRoomStore.isLegacyMigrationMarked(
                        prefs));
    }

    @Test
    public void migrationCanRetryAfterExactPartialImport() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        HouseholdProfile legacy =
                profile(
                        PROFILE_ONE,
                        OWNER_ONE,
                        1L,
                        70.0f);

        putLegacy(
                prefs,
                List.of(
                        legacy));

        FakeDao dao =
                new FakeDao();

        dao.insert(
                entity(
                        legacy,
                        0L));

        assertTrue(
                HouseholdProfileRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertEquals(
                1,
                dao.count());
    }

    @Test
    public void acceptsNewerRevisionFromSameOwnerAndKeepsPosition() {
        FakeDao dao =
                new FakeDao();

        assertTrue(
                HouseholdProfileRoomStore.upsert(
                        dao,
                        profile(
                                PROFILE_ONE,
                                OWNER_ONE,
                                1L,
                                70.0f)));

        assertTrue(
                HouseholdProfileRoomStore.upsert(
                        dao,
                        profile(
                                PROFILE_TWO,
                                OWNER_TWO,
                                1L,
                                80.0f)));

        assertTrue(
                HouseholdProfileRoomStore.upsert(
                        dao,
                        profile(
                                PROFILE_ONE,
                                OWNER_ONE,
                                2L,
                                71.0f)));

        List<HouseholdProfile> stored =
                HouseholdProfileRoomStore.load(
                        dao);

        assertEquals(
                2,
                stored.size());

        assertEquals(
                PROFILE_ONE,
                stored.get(0).profileId);

        assertEquals(
                2L,
                stored.get(0).updatedAtMs);

        assertEquals(
                71.0f,
                stored.get(0).referenceWeightKg,
                0.001f);

        assertEquals(
                PROFILE_TWO,
                stored.get(1).profileId);
    }

    @Test
    public void rejectsDifferentOwnerAndOlderRevision() {
        FakeDao dao =
                new FakeDao();

        assertTrue(
                HouseholdProfileRoomStore.upsert(
                        dao,
                        profile(
                                PROFILE_ONE,
                                OWNER_ONE,
                                5L,
                                70.0f)));

        assertFalse(
                HouseholdProfileRoomStore.upsert(
                        dao,
                        profile(
                                PROFILE_ONE,
                                OWNER_TWO,
                                6L,
                                80.0f)));

        assertFalse(
                HouseholdProfileRoomStore.upsert(
                        dao,
                        profile(
                                PROFILE_ONE,
                                OWNER_ONE,
                                5L,
                                71.0f)));

        assertFalse(
                HouseholdProfileRoomStore.upsert(
                        dao,
                        profile(
                                PROFILE_ONE,
                                OWNER_ONE,
                                4L,
                                72.0f)));

        HouseholdProfile stored =
                HouseholdProfileRoomStore.find(
                        dao,
                        PROFILE_ONE);

        assertEquals(
                OWNER_ONE,
                stored.ownerDeviceId);

        assertEquals(
                5L,
                stored.updatedAtMs);

        assertEquals(
                70.0f,
                stored.referenceWeightKg,
                0.001f);
    }

    @Test
    public void removeOwnerExceptPreservesRetainedAndOtherOwner() {
        FakeDao dao =
                new FakeDao();

        HouseholdProfileRoomStore.upsert(
                dao,
                profile(
                        PROFILE_ONE,
                        OWNER_ONE,
                        1L,
                        70.0f));

        HouseholdProfileRoomStore.upsert(
                dao,
                profile(
                        PROFILE_TWO,
                        OWNER_ONE,
                        1L,
                        71.0f));

        HouseholdProfileRoomStore.upsert(
                dao,
                profile(
                        PROFILE_THREE,
                        OWNER_TWO,
                        1L,
                        80.0f));

        assertEquals(
                1,
                HouseholdProfileRoomStore.removeOwnerExcept(
                        dao,
                        OWNER_ONE,
                        List.of(
                                PROFILE_ONE)));

        assertTrue(
                HouseholdProfileRoomStore.find(
                        dao,
                        PROFILE_ONE) != null);

        assertTrue(
                HouseholdProfileRoomStore.find(
                        dao,
                        PROFILE_TWO) == null);

        assertTrue(
                HouseholdProfileRoomStore.find(
                        dao,
                        PROFILE_THREE) != null);
    }

    @Test
    public void invalidManifestDoesNotDeleteAnything() {
        FakeDao dao =
                new FakeDao();

        HouseholdProfileRoomStore.upsert(
                dao,
                profile(
                        PROFILE_ONE,
                        OWNER_ONE,
                        1L,
                        70.0f));

        HouseholdProfileRoomStore.upsert(
                dao,
                profile(
                        PROFILE_TWO,
                        OWNER_ONE,
                        1L,
                        71.0f));

        assertEquals(
                0,
                HouseholdProfileRoomStore.removeOwnerExcept(
                        dao,
                        OWNER_ONE,
                        List.of(
                                "ungueltige-profil-id")));

        assertEquals(
                2,
                dao.count());
    }

    @Test
    public void removeOwnerRemovesOnlyItsProfiles() {
        FakeDao dao =
                new FakeDao();

        HouseholdProfileRoomStore.upsert(
                dao,
                profile(
                        PROFILE_ONE,
                        OWNER_ONE,
                        1L,
                        70.0f));

        HouseholdProfileRoomStore.upsert(
                dao,
                profile(
                        PROFILE_TWO,
                        OWNER_ONE,
                        1L,
                        71.0f));

        HouseholdProfileRoomStore.upsert(
                dao,
                profile(
                        PROFILE_THREE,
                        OWNER_TWO,
                        1L,
                        80.0f));

        assertEquals(
                2,
                HouseholdProfileRoomStore.removeOwner(
                        dao,
                        OWNER_ONE));

        assertEquals(
                1,
                dao.count());

        assertTrue(
                HouseholdProfileRoomStore.find(
                        dao,
                        PROFILE_THREE) != null);
    }

    @Test
    public void keepsMoreThanThousandProfiles() {
        FakeDao dao =
                new FakeDao();

        for (int index = 0;
             index <= 1000;
             index++) {
            String profileId =
                    String.format(
                            "%08d-1111-4111-8111-%012d",
                            index,
                            index);

            assertTrue(
                    HouseholdProfileRoomStore.upsert(
                            dao,
                            profile(
                                    profileId,
                                    OWNER_ONE,
                                    index + 1L,
                                    70.0f)));
        }

        assertEquals(
                1001,
                dao.count());
    }

    private static HouseholdProfile profile(
            String profileId,
            String ownerDeviceId,
            long revision,
            float referenceWeightKg) {
        return new HouseholdProfile(
                profileId,
                "Testprofil",
                ownerDeviceId,
                referenceWeightKg,
                5.0f,
                true,
                revision);
    }

    private static HouseholdProfileEntity entity(
            HouseholdProfile profile,
            long sortOrder) {
        return new HouseholdProfileEntity(
                profile.profileId,
                profile.name,
                profile.ownerDeviceId,
                profile.referenceWeightKg,
                profile.toleranceKg,
                profile.active,
                profile.updatedAtMs,
                sortOrder);
    }

    private static void putLegacy(
            InMemorySharedPreferences prefs,
            List<HouseholdProfile> profiles) {
        JSONArray array =
                new JSONArray();

        try {
            for (HouseholdProfile profile :
                    profiles) {
                array.put(
                        profile.toJson());
            }
        } catch (JSONException exception) {
            throw new AssertionError(
                    exception);
        }

        prefs.edit()
                .putString(
                        "profiles",
                        array.toString())
                .commit();
    }

    private static final class FakeDao
            implements HouseholdProfileDao {
        private final Map<String, HouseholdProfileEntity> items =
                new LinkedHashMap<>();

        @Override
        public List<HouseholdProfileEntity> loadAll() {
            List<HouseholdProfileEntity> result =
                    new ArrayList<>(
                            items.values());

            result.sort(
                    Comparator.comparingLong(
                            entity ->
                                    entity.sortOrder));

            return result;
        }

        @Override
        public HouseholdProfileEntity find(
                String profileId) {
            return items.get(
                    profileId);
        }

        @Override
        public long insert(
                HouseholdProfileEntity entity) {
            if (items.containsKey(
                    entity.profileId)) {
                return -1L;
            }

            items.put(
                    entity.profileId,
                    entity);

            return 1L;
        }

        @Override
        public int update(
                HouseholdProfileEntity entity) {
            if (!items.containsKey(
                    entity.profileId)) {
                return 0;
            }

            items.put(
                    entity.profileId,
                    entity);

            return 1;
        }

        @Override
        public int delete(
                String profileId) {
            return items.remove(
                    profileId) == null
                    ? 0
                    : 1;
        }

        @Override
        public int deleteOwner(
                String ownerDeviceId) {
            int before =
                    items.size();

            items.entrySet()
                    .removeIf(
                            entry ->
                                    ownerDeviceId.equals(
                                            entry.getValue()
                                                    .ownerDeviceId));

            return before
                    - items.size();
        }

        @Override
        public int count() {
            return items.size();
        }

        @Override
        public Long maxSortOrder() {
            Long maximum =
                    null;

            for (HouseholdProfileEntity entity :
                    items.values()) {
                if (maximum == null
                        || entity.sortOrder
                                > maximum) {
                    maximum =
                            entity.sortOrder;
                }
            }

            return maximum;
        }
    }
}
