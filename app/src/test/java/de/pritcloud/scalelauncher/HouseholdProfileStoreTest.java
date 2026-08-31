package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class HouseholdProfileStoreTest {
    private static final String PROFILE_ID =
            "22222222-2222-4222-8222-222222222222";

    private static final String OWNER_ONE =
            "11111111-1111-4111-8111-111111111111";

    private static final String OWNER_TWO =
            "33333333-3333-4333-8333-333333333333";

    @Test
    public void acceptsNewerRevisionFromSameOwner() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        assertTrue(
                HouseholdProfileStore.upsert(
                        prefs,
                        profile(
                                OWNER_ONE,
                                1L,
                                70.0f)));

        assertTrue(
                HouseholdProfileStore.upsert(
                        prefs,
                        profile(
                                OWNER_ONE,
                                2L,
                                71.0f)));

        List<HouseholdProfile> stored =
                HouseholdProfileStore.load(prefs);

        assertEquals(1, stored.size());
        assertEquals(OWNER_ONE, stored.get(0).ownerDeviceId);
        assertEquals(2L, stored.get(0).updatedAtMs);
        assertEquals(71.0f, stored.get(0).referenceWeightKg, 0.001f);
    }

    @Test
    public void rejectsNewerRevisionFromDifferentOwner() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        assertTrue(
                HouseholdProfileStore.upsert(
                        prefs,
                        profile(
                                OWNER_ONE,
                                1L,
                                70.0f)));

        assertFalse(
                HouseholdProfileStore.upsert(
                        prefs,
                        profile(
                                OWNER_TWO,
                                2L,
                                80.0f)));

        List<HouseholdProfile> stored =
                HouseholdProfileStore.load(prefs);

        assertEquals(1, stored.size());
        assertEquals(OWNER_ONE, stored.get(0).ownerDeviceId);
        assertEquals(1L, stored.get(0).updatedAtMs);
        assertEquals(70.0f, stored.get(0).referenceWeightKg, 0.001f);
    }


    @Test
    public void removeOwnerRemovesOnlyItsProfiles() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        assertTrue(
                HouseholdProfileStore.upsert(
                        prefs,
                        new HouseholdProfile(
                                "44444444-4444-4444-8444-444444444444",
                                "Profil 1",
                                OWNER_ONE,
                                70.0f,
                                5.0f,
                                true,
                                1L)));

        assertTrue(
                HouseholdProfileStore.upsert(
                        prefs,
                        new HouseholdProfile(
                                "55555555-5555-4555-8555-555555555555",
                                "Profil 2",
                                OWNER_ONE,
                                71.0f,
                                5.0f,
                                true,
                                1L)));

        assertTrue(
                HouseholdProfileStore.upsert(
                        prefs,
                        new HouseholdProfile(
                                "66666666-6666-4666-8666-666666666666",
                                "Profil 3",
                                OWNER_TWO,
                                80.0f,
                                5.0f,
                                true,
                                1L)));

        assertEquals(
                2,
                HouseholdProfileStore.removeOwner(
                        prefs,
                        OWNER_ONE));

        List<HouseholdProfile> remaining =
                HouseholdProfileStore.load(
                        prefs);

        assertEquals(
                1,
                remaining.size());

        assertEquals(
                OWNER_TWO,
                remaining.get(0).ownerDeviceId);
    }



    @Test
    public void removeOwnerExceptWithEmptyManifestRemovesAllOwnerProfiles() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        assertTrue(
                HouseholdProfileStore.upsert(
                        prefs,
                        new HouseholdProfile(
                                "44444444-4444-4444-8444-444444444444",
                                "Profil 1",
                                OWNER_ONE,
                                70.0f,
                                5.0f,
                                true,
                                1L)));

        assertTrue(
                HouseholdProfileStore.upsert(
                        prefs,
                        new HouseholdProfile(
                                "55555555-5555-4555-8555-555555555555",
                                "Profil 2",
                                OWNER_ONE,
                                71.0f,
                                5.0f,
                                true,
                                1L)));

        assertTrue(
                HouseholdProfileStore.upsert(
                        prefs,
                        new HouseholdProfile(
                                "66666666-6666-4666-8666-666666666666",
                                "Profil 3",
                                OWNER_TWO,
                                80.0f,
                                5.0f,
                                true,
                                1L)));

        assertEquals(
                2,
                HouseholdProfileStore.removeOwnerExcept(
                        prefs,
                        OWNER_ONE,
                        List.of()));

        List<HouseholdProfile> remaining =
                HouseholdProfileStore.load(
                        prefs);

        assertEquals(
                1,
                remaining.size());

        assertEquals(
                OWNER_TWO,
                remaining.get(0).ownerDeviceId);
    }



    @Test
    public void removeOwnerExceptKeepsRetainedAndOtherOwnerProfiles() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        String retainedProfileId =
                "44444444-4444-4444-8444-444444444444";

        String removedProfileId =
                "55555555-5555-4555-8555-555555555555";

        String otherOwnerProfileId =
                "66666666-6666-4666-8666-666666666666";

        assertTrue(
                HouseholdProfileStore.upsert(
                        prefs,
                        new HouseholdProfile(
                                retainedProfileId,
                                "Behalten",
                                OWNER_ONE,
                                70.0f,
                                5.0f,
                                true,
                                1L)));

        assertTrue(
                HouseholdProfileStore.upsert(
                        prefs,
                        new HouseholdProfile(
                                removedProfileId,
                                "Entfernen",
                                OWNER_ONE,
                                71.0f,
                                5.0f,
                                true,
                                1L)));

        assertTrue(
                HouseholdProfileStore.upsert(
                        prefs,
                        new HouseholdProfile(
                                otherOwnerProfileId,
                                "Anderer Besitzer",
                                OWNER_TWO,
                                80.0f,
                                5.0f,
                                true,
                                1L)));

        assertEquals(
                1,
                HouseholdProfileStore.removeOwnerExcept(
                        prefs,
                        OWNER_ONE,
                        List.of(retainedProfileId)));

        List<HouseholdProfile> remaining =
                HouseholdProfileStore.load(
                        prefs);

        assertEquals(
                2,
                remaining.size());

        boolean retainedFound = false;
        boolean otherOwnerFound = false;

        for (HouseholdProfile profile : remaining) {
            if (retainedProfileId.equals(profile.profileId)
                    && OWNER_ONE.equals(profile.ownerDeviceId)) {
                retainedFound = true;
            }

            if (otherOwnerProfileId.equals(profile.profileId)
                    && OWNER_TWO.equals(profile.ownerDeviceId)) {
                otherOwnerFound = true;
            }
        }

        assertTrue(retainedFound);
        assertTrue(otherOwnerFound);
    }


    private static HouseholdProfile profile(
            String ownerDeviceId,
            long revision,
            float referenceWeightKg) {
        return new HouseholdProfile(
                PROFILE_ID,
                "Testprofil",
                ownerDeviceId,
                referenceWeightKg,
                5.0f,
                true,
                revision);
    }
}
