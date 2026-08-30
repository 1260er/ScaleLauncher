package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HouseholdProfileSyncTest {
    private static final String LOCAL_DEVICE_ID =
            "11111111-1111-4111-8111-111111111111";

    private static final String REMOTE_DEVICE_ID =
            "33333333-3333-4333-8333-333333333333";

    private static final String PROFILE_ID =
            "22222222-2222-4222-8222-222222222222";

    @Test
    public void allowsProfileFromLocalOwner() {
        assertFalse(
                HouseholdProfileSync.hasOwnerConflict(
                        LOCAL_DEVICE_ID,
                        profile(LOCAL_DEVICE_ID)));
    }

    @Test
    public void rejectsProfileFromRemoteOwner() {
        assertTrue(
                HouseholdProfileSync.hasOwnerConflict(
                        LOCAL_DEVICE_ID,
                        profile(REMOTE_DEVICE_ID)));
    }

    private static HouseholdProfile profile(
            String ownerDeviceId) {
        return new HouseholdProfile(
                PROFILE_ID,
                "Testprofil",
                ownerDeviceId,
                70.0f,
                5.0f,
                true,
                2L);
    }
}
