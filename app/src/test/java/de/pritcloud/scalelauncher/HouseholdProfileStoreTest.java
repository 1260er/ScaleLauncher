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
