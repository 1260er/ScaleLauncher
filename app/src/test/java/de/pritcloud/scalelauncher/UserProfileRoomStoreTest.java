package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UserProfileRoomStoreTest {
    private static final String LOCAL_DEVICE_ID =
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    private static final String REMOTE_DEVICE_ID =
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    private static final String HOUSEHOLD_ONE =
            "11111111-1111-4111-8111-111111111111";

    @Test
    public void migratesLegacyWithAllFieldsAndKeepsJson() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        UserProfile first =
                profile(
                        7L,
                        "Profil 7",
                        false);

        first.birthDateIso =
                "1980-05-12";

        first.heightCm =
                181.5f;

        first.male =
                true;

        first.referenceWeightKg =
                70.7f;

        first.toleranceKg =
                2.5f;

        first.ownerDeviceId =
                LOCAL_DEVICE_ID;

        first.householdProfileId =
                HOUSEHOLD_ONE;

        first.householdUpdatedAtMs =
                12345L;

        UserProfile second =
                profile(
                        8L,
                        "Profil 8",
                        true);

        putLegacy(
                prefs,
                List.of(
                        first,
                        second));

        String legacyJson =
                prefs.getString(
                        "user_profiles_json",
                        "");

        FakeDao dao =
                new FakeDao();

        assertTrue(
                UserProfileRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertTrue(
                UserProfileRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                legacyJson,
                prefs.getString(
                        "user_profiles_json",
                        ""));

        List<UserProfile> stored =
                UserProfileRoomStore.load(
                        dao);

        assertEquals(
                2,
                stored.size());

        UserProfile migrated =
                stored.get(0);

        assertEquals(
                7L,
                migrated.userId);

        assertEquals(
                "Profil 7",
                migrated.name);

        assertFalse(
                migrated.enabled);

        assertEquals(
                "1980-05-12",
                migrated.birthDateIso);

        assertEquals(
                181.5f,
                migrated.heightCm,
                0.001f);

        assertTrue(
                migrated.male);

        assertEquals(
                70.7f,
                migrated.referenceWeightKg,
                0.001f);

        assertEquals(
                2.5f,
                migrated.toleranceKg,
                0.001f);

        assertEquals(
                LOCAL_DEVICE_ID,
                migrated.ownerDeviceId);

        assertEquals(
                HOUSEHOLD_ONE,
                migrated.householdProfileId);

        assertEquals(
                12345L,
                migrated.householdUpdatedAtMs);

        assertEquals(
                8L,
                stored.get(1).userId);
    }

    @Test
    public void migratesStableProfileWithoutHouseholdFields() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putString(
                        "user_profiles_json",
                        "[{"
                                + "\"userId\":7,"
                                + "\"name\":\"Alt\","
                                + "\"enabled\":false,"
                                + "\"birthDate\":\"1980-05-12\","
                                + "\"heightCm\":181.5,"
                                + "\"male\":true,"
                                + "\"referenceWeightKg\":70.7,"
                                + "\"toleranceKg\":2.5"
                                + "}]")
                .commit();

        FakeDao dao =
                new FakeDao();

        assertTrue(
                UserProfileRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        UserProfile migrated =
                UserProfileRoomStore.load(
                        dao)
                        .get(0);

        assertFalse(
                migrated.enabled);

        assertEquals(
                "",
                migrated.ownerDeviceId);

        assertEquals(
                "",
                migrated.householdProfileId);

        assertEquals(
                0L,
                migrated.householdUpdatedAtMs);

        assertEquals(
                70.7f,
                migrated.referenceWeightKg,
                0.001f);
    }

    @Test
    public void blankLegacyMigratesAsEmpty() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        FakeDao dao =
                new FakeDao();

        assertTrue(
                UserProfileRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertTrue(
                UserProfileRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                0,
                dao.count());
    }

    @Test
    public void malformedLegacyFailsClosed() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putString(
                        "user_profiles_json",
                        "not-json")
                .commit();

        FakeDao dao =
                new FakeDao();

        assertFalse(
                UserProfileRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                UserProfileRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                0,
                dao.count());
    }

    @Test
    public void invalidLegacyEntryFailsClosed() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putString(
                        "user_profiles_json",
                        "[{}]")
                .commit();

        FakeDao dao =
                new FakeDao();

        assertFalse(
                UserProfileRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                UserProfileRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                0,
                dao.count());
    }

    @Test
    public void duplicateUserIdFailsClosed() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        putLegacy(
                prefs,
                List.of(
                        profile(
                                7L,
                                "Erste Version",
                                true),
                        profile(
                                7L,
                                "Zweite Version",
                                false)));

        FakeDao dao =
                new FakeDao();

        assertFalse(
                UserProfileRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                UserProfileRoomStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                0,
                dao.count());
    }

    @Test
    public void conflictingPreexistingRoomRowFailsClosed() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        UserProfile legacy =
                profile(
                        7L,
                        "Legacy",
                        true);

        putLegacy(
                prefs,
                List.of(
                        legacy));

        FakeDao dao =
                new FakeDao();

        dao.insert(
                entity(
                        profile(
                                7L,
                                "Konflikt",
                                false),
                        0L));

        assertFalse(
                UserProfileRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                UserProfileRoomStore.isLegacyMigrationMarked(
                        prefs));
    }

    @Test
    public void extraPreexistingRoomRowFailsClosed() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        UserProfile legacy =
                profile(
                        7L,
                        "Legacy",
                        true);

        putLegacy(
                prefs,
                List.of(
                        legacy));

        FakeDao dao =
                new FakeDao();

        dao.insert(
                entity(
                        profile(
                                8L,
                                "Nicht im Legacy",
                                true),
                        1L));

        assertFalse(
                UserProfileRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                UserProfileRoomStore.isLegacyMigrationMarked(
                        prefs));
    }

    @Test
    public void migrationCanRetryAfterExactPartialImport() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        UserProfile first =
                profile(
                        7L,
                        "Profil 7",
                        true);

        UserProfile second =
                profile(
                        8L,
                        "Profil 8",
                        false);

        putLegacy(
                prefs,
                List.of(
                        first,
                        second));

        FakeDao dao =
                new FakeDao();

        dao.insert(
                entity(
                        first,
                        0L));

        assertTrue(
                UserProfileRoomStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertEquals(
                2,
                dao.count());
    }

    @Test
    public void saveReplacesWholeListAndKeepsOrder() {
        FakeDao dao =
                new FakeDao();

        UserProfileRoomStore.save(
                dao,
                List.of(
                        profile(
                                7L,
                                "Alt 7",
                                true),
                        profile(
                                8L,
                                "Alt 8",
                                true)));

        UserProfile disabled =
                profile(
                        9L,
                        "Neu 9",
                        false);

        UserProfileRoomStore.save(
                dao,
                List.of(
                        disabled,
                        profile(
                                7L,
                                "Neu 7",
                                true)));

        List<UserProfile> stored =
                UserProfileRoomStore.load(
                        dao);

        assertEquals(
                2,
                stored.size());

        assertEquals(
                9L,
                stored.get(0).userId);

        assertFalse(
                stored.get(0).enabled);

        assertEquals(
                7L,
                stored.get(1).userId);

        assertEquals(
                "Neu 7",
                stored.get(1).name);

        assertNull(
                UserProfileRoomStore.find(
                        stored,
                        8L));
    }

    @Test
    public void synchronizePreservesConfigurationAndDisabledState() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        FakeDao dao =
                new FakeDao();

        UserProfile existing =
                profile(
                        7L,
                        "Alter Name",
                        false);

        existing.birthDateIso =
                "1980-05-12";

        existing.heightCm =
                181.5f;

        existing.male =
                true;

        existing.referenceWeightKg =
                70.7f;

        existing.toleranceKg =
                2.5f;

        existing.ownerDeviceId =
                REMOTE_DEVICE_ID;

        existing.householdProfileId =
                HOUSEHOLD_ONE;

        existing.householdUpdatedAtMs =
                10L;

        UserProfileRoomStore.save(
                dao,
                List.of(
                        existing,
                        profile(
                                8L,
                                "Entfernt",
                                true)));

        List<UserProfile> synchronizedProfiles =
                UserProfileRoomStore.synchronize(
                        dao,
                        prefs,
                        List.of(
                                new OpenScaleProvider.User(
                                        7L,
                                        "Neuer Name")),
                        LOCAL_DEVICE_ID);

        assertEquals(
                1,
                synchronizedProfiles.size());

        UserProfile profile =
                synchronizedProfiles.get(0);

        assertEquals(
                "Neuer Name",
                profile.name);

        assertFalse(
                profile.enabled);

        assertEquals(
                "1980-05-12",
                profile.birthDateIso);

        assertEquals(
                181.5f,
                profile.heightCm,
                0.001f);

        assertTrue(
                profile.male);

        assertEquals(
                70.7f,
                profile.referenceWeightKg,
                0.001f);

        assertEquals(
                2.5f,
                profile.toleranceKg,
                0.001f);

        assertEquals(
                LOCAL_DEVICE_ID,
                profile.ownerDeviceId);

        assertTrue(
                UserProfile.isValidHouseholdProfileId(
                        profile.householdProfileId));

        assertNotEquals(
                HOUSEHOLD_ONE,
                profile.householdProfileId);

        assertTrue(
                profile.householdUpdatedAtMs > 10L);

        assertEquals(
                1,
                dao.count());
    }

    @Test
    public void synchronizeMigratesLegacySettingsForFirstOpenScaleUser() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putLong(
                        "openscale_user_id",
                        7L)
                .putString(
                        "birth_date",
                        "1980-05-12")
                .putFloat(
                        "height_cm",
                        181.5f)
                .putInt(
                        "sex",
                        1)
                .putBoolean(
                        "health_connect_enabled",
                        true)
                .commit();

        FakeDao dao =
                new FakeDao();

        List<UserProfile> synchronizedProfiles =
                UserProfileRoomStore.synchronize(
                        dao,
                        prefs,
                        List.of(
                                new OpenScaleProvider.User(
                                        7L,
                                        "Andre")),
                        LOCAL_DEVICE_ID);

        assertEquals(
                1,
                synchronizedProfiles.size());

        UserProfile profile =
                synchronizedProfiles.get(0);

        assertTrue(
                profile.enabled);

        assertEquals(
                "1980-05-12",
                profile.birthDateIso);

        assertEquals(
                181.5f,
                profile.heightCm,
                0.001f);

        assertTrue(
                profile.male);

        assertEquals(
                LOCAL_DEVICE_ID,
                profile.ownerDeviceId);

        assertTrue(
                UserProfile.isValidHouseholdProfileId(
                        profile.householdProfileId));

        assertEquals(
                7L,
                prefs.getLong(
                        "health_connect_user_id",
                        -1L));
    }

    @Test
    public void synchronizeCleansSelectionsForRemovedUsers() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putLong(
                        "health_connect_user_id",
                        8L)
                .putLong(
                        "profile_editor_user_id",
                        8L)
                .putLong(
                        "openscale_user_id",
                        8L)
                .commit();

        FakeDao dao =
                new FakeDao();

        UserProfileRoomStore.save(
                dao,
                List.of(
                        profile(
                                7L,
                                "Bleibt",
                                true),
                        profile(
                                8L,
                                "Entfernt",
                                true)));

        UserProfileRoomStore.synchronize(
                dao,
                prefs,
                List.of(
                        new OpenScaleProvider.User(
                                7L,
                                "Bleibt")),
                LOCAL_DEVICE_ID);

        assertEquals(
                -1L,
                prefs.getLong(
                        "health_connect_user_id",
                        -1L));

        assertEquals(
                -1L,
                prefs.getLong(
                        "profile_editor_user_id",
                        -1L));

        assertEquals(
                -1L,
                prefs.getLong(
                        "openscale_user_id",
                        -1L));
    }

    @Test
    public void updateReferenceWeightChangesOnlyRequestedProfile() {
        FakeDao dao =
                new FakeDao();

        UserProfile first =
                profile(
                        7L,
                        "Profil 7",
                        true);

        first.referenceWeightKg =
                70.0f;

        UserProfile second =
                profile(
                        8L,
                        "Profil 8",
                        true);

        second.referenceWeightKg =
                80.0f;

        UserProfileRoomStore.save(
                dao,
                List.of(
                        first,
                        second));

        UserProfileRoomStore.updateReferenceWeight(
                dao,
                7L,
                71.5f);

        List<UserProfile> stored =
                UserProfileRoomStore.load(
                        dao);

        assertEquals(
                71.5f,
                stored.get(0).referenceWeightKg,
                0.001f);

        assertEquals(
                80.0f,
                stored.get(1).referenceWeightKg,
                0.001f);

        UserProfileRoomStore.updateReferenceWeight(
                dao,
                7L,
                Float.NaN);

        assertEquals(
                71.5f,
                UserProfileRoomStore.load(
                        dao)
                        .get(0)
                        .referenceWeightKg,
                0.001f);
    }

    @Test
    public void enabledPreservesProfileOrder() {
        UserProfile first =
                profile(
                        7L,
                        "Erstes",
                        true);

        UserProfile second =
                profile(
                        8L,
                        "Zweites",
                        false);

        UserProfile third =
                profile(
                        9L,
                        "Drittes",
                        true);

        List<UserProfile> enabled =
                UserProfileRoomStore.enabled(
                        List.of(
                                first,
                                second,
                                third));

        assertEquals(
                2,
                enabled.size());

        assertEquals(
                7L,
                enabled.get(0).userId);

        assertEquals(
                9L,
                enabled.get(1).userId);
    }

    @Test
    public void savesMoreThanThousandProfiles() {
        FakeDao dao =
                new FakeDao();

        List<UserProfile> profiles =
                new ArrayList<>();

        for (int index = 0;
             index <= 1000;
             index++) {
            profiles.add(
                    profile(
                            index,
                            "Profil " + index,
                            true));
        }

        UserProfileRoomStore.save(
                dao,
                profiles);

        assertEquals(
                1001,
                dao.count());

        assertEquals(
                1000L,
                UserProfileRoomStore.load(
                        dao)
                        .get(1000)
                        .userId);
    }

    private static UserProfile profile(
            long userId,
            String name,
            boolean enabled) {
        UserProfile profile =
                new UserProfile(
                        userId,
                        name);

        profile.enabled =
                enabled;

        return profile;
    }

    private static UserProfileEntity entity(
            UserProfile profile,
            long sortOrder) {
        return new UserProfileEntity(
                profile.userId,
                profile.name == null
                        ? ""
                        : profile.name,
                profile.enabled,
                profile.birthDateIso == null
                        ? ""
                        : profile.birthDateIso,
                profile.heightCm,
                profile.male,
                profile.referenceWeightKg,
                profile.toleranceKg,
                profile.ownerDeviceId == null
                        ? ""
                        : profile.ownerDeviceId,
                profile.householdProfileId == null
                        ? ""
                        : profile.householdProfileId,
                profile.householdUpdatedAtMs,
                sortOrder);
    }

    private static void putLegacy(
            InMemorySharedPreferences prefs,
            List<UserProfile> profiles) {
        JSONArray array =
                new JSONArray();

        try {
            for (UserProfile profile :
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
                        "user_profiles_json",
                        array.toString())
                .commit();
    }

    private static final class FakeDao
            implements UserProfileDao {
        private final Map<Long, UserProfileEntity> items =
                new LinkedHashMap<>();

        @Override
        public List<UserProfileEntity> loadAll() {
            List<UserProfileEntity> result =
                    new ArrayList<>(
                            items.values());

            result.sort(
                    Comparator.comparingLong(
                            entity ->
                                    entity.sortOrder));

            return result;
        }

        @Override
        public UserProfileEntity find(
                long userId) {
            return items.get(
                    userId);
        }

        @Override
        public long insert(
                UserProfileEntity entity) {
            if (items.containsKey(
                    entity.userId)) {
                return -1L;
            }

            items.put(
                    entity.userId,
                    entity);

            return 1L;
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

            for (UserProfileEntity entity :
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
