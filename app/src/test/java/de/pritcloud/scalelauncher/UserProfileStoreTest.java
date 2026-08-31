package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class UserProfileStoreTest {
    private static final String LOCAL_DEVICE_ID =
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Test
    public void stableV110ProfileMigratesWithoutLosingConfiguration() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        /*
         * Shape written by the stable v1.1.0 UserProfile.
         * Household ownership, profile ID and revision did not exist yet.
         */
        prefs.edit()
                .putString(
                        "user_profiles_json",
                        "[{"
                                + "\"userId\":7,"
                                + "\"name\":\"Andre Alt\","
                                + "\"enabled\":true,"
                                + "\"birthDate\":\"1980-05-12\","
                                + "\"heightCm\":181.5,"
                                + "\"male\":true,"
                                + "\"referenceWeightKg\":70.7,"
                                + "\"toleranceKg\":2.5"
                                + "}]")
                .commit();

        List<UserProfile> migrated =
                UserProfileStore.synchronize(
                        prefs,
                        List.of(
                                new OpenScaleProvider.User(
                                        7L,
                                        "Andre Neu")),
                        LOCAL_DEVICE_ID);

        assertEquals(
                1,
                migrated.size());

        UserProfile profile =
                migrated.get(0);

        assertEquals(
                7L,
                profile.userId);

        /*
         * The display name follows the current openScale user,
         * while the locally configured profile data survives the upgrade.
         */
        assertEquals(
                "Andre Neu",
                profile.name);

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

        assertTrue(
                profile.householdUpdatedAtMs > 0L);

        List<UserProfile> persisted =
                UserProfileStore.load(
                        prefs);

        assertEquals(
                1,
                persisted.size());

        assertEquals(
                profile.householdProfileId,
                persisted.get(0).householdProfileId);

        assertEquals(
                LOCAL_DEVICE_ID,
                persisted.get(0).ownerDeviceId);
    }

    @Test
    public void stableProfileDisabledStateIsPreservedDuringMigration() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putString(
                        "user_profiles_json",
                        "[{"
                                + "\"userId\":8,"
                                + "\"name\":\"Disabled\","
                                + "\"enabled\":false,"
                                + "\"birthDate\":\"1985-01-01\","
                                + "\"heightCm\":170.0,"
                                + "\"male\":false,"
                                + "\"referenceWeightKg\":60.0,"
                                + "\"toleranceKg\":2.0"
                                + "}]")
                .commit();

        List<UserProfile> migrated =
                UserProfileStore.synchronize(
                        prefs,
                        List.of(
                                new OpenScaleProvider.User(
                                        8L,
                                        "Disabled")),
                        LOCAL_DEVICE_ID);

        assertEquals(
                1,
                migrated.size());

        assertFalse(
                migrated.get(0).enabled);

        assertEquals(
                LOCAL_DEVICE_ID,
                migrated.get(0).ownerDeviceId);

        assertTrue(
                UserProfile.isValidHouseholdProfileId(
                        migrated.get(0).householdProfileId));
    }
}
