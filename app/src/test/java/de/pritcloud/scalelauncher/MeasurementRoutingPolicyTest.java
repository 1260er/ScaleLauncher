package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class MeasurementRoutingPolicyTest {
    @Test
    public void localNoMatchWinsOverStaleHouseholdAmbiguity() {
        assertFalse(
                MeasurementRoutingPolicy.shouldCreateHouseholdAmbiguousPending(
                        UserMatcher.Status.NO_MATCH,
                        HouseholdMeasurementRouter.Status.AMBIGUOUS));
    }

    @Test
    public void realHouseholdAmbiguityStillCreatesPending() {
        assertTrue(
                MeasurementRoutingPolicy.shouldCreateHouseholdAmbiguousPending(
                        UserMatcher.Status.MATCHED,
                        HouseholdMeasurementRouter.Status.AMBIGUOUS));

        assertTrue(
                MeasurementRoutingPolicy.shouldCreateHouseholdAmbiguousPending(
                        UserMatcher.Status.AMBIGUOUS,
                        HouseholdMeasurementRouter.Status.AMBIGUOUS));
    }

    @Test
    public void nonAmbiguousHouseholdNeverUsesAmbiguousPath() {
        assertFalse(
                MeasurementRoutingPolicy.shouldCreateHouseholdAmbiguousPending(
                        UserMatcher.Status.MATCHED,
                        HouseholdMeasurementRouter.Status.UNIQUE));

        assertFalse(
                MeasurementRoutingPolicy.shouldCreateHouseholdAmbiguousPending(
                        UserMatcher.Status.NO_MATCH,
                        HouseholdMeasurementRouter.Status.NO_MATCH));
    }

    @Test
    public void normalPendingAutoResolvesWhenOneCandidateRemains() {
        assertTrue(
                MeasurementRoutingPolicy.shouldAutoResolveSingleRemainingCandidate(
                        false,
                        1));
    }

    @Test
    public void normalPendingStaysOpenWhenMultipleCandidatesRemain() {
        assertFalse(
                MeasurementRoutingPolicy.shouldAutoResolveSingleRemainingCandidate(
                        false,
                        2));
    }

    @Test
    public void manualRescueNeverAutoResolvesLastCandidate() {
        assertFalse(
                MeasurementRoutingPolicy.shouldAutoResolveSingleRemainingCandidate(
                        true,
                        1));
    }

    @Test
    public void userMatcherKeepsMatchedAmbiguousAndNoMatchSemantics() {
        UserProfile andre =
                localProfile(
                        1L,
                        "Andre",
                        80.0f,
                        2.0f);

        UserProfile ela =
                localProfile(
                        2L,
                        "Ela",
                        80.5f,
                        2.0f);

        assertEquals(
                UserMatcher.Status.NO_MATCH,
                UserMatcher.match(
                        List.of(
                                andre,
                                ela),
                        70.7f).status);

        assertEquals(
                UserMatcher.Status.MATCHED,
                UserMatcher.match(
                        List.of(
                                andre),
                        80.5f).status);

        assertEquals(
                UserMatcher.Status.AMBIGUOUS,
                UserMatcher.match(
                        List.of(
                                andre,
                                ela),
                        80.5f).status);
    }

    @Test
    public void householdRouterKeepsUniqueAmbiguousAndNoMatchSemantics() {
        HouseholdProfile andre =
                householdProfile(
                        "11111111-1111-1111-1111-111111111111",
                        "Andre",
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                        80.0f,
                        2.0f);

        HouseholdProfile ela =
                householdProfile(
                        "22222222-2222-2222-2222-222222222222",
                        "Ela",
                        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                        80.5f,
                        2.0f);

        assertEquals(
                HouseholdMeasurementRouter.Status.NO_MATCH,
                HouseholdMeasurementRouter.match(
                        List.of(
                                andre,
                                ela),
                        70.7f).status);

        assertEquals(
                HouseholdMeasurementRouter.Status.UNIQUE,
                HouseholdMeasurementRouter.match(
                        List.of(
                                andre),
                        80.5f).status);

        assertEquals(
                HouseholdMeasurementRouter.Status.AMBIGUOUS,
                HouseholdMeasurementRouter.match(
                        List.of(
                                andre,
                                ela),
                        80.5f).status);
    }

    private static UserProfile localProfile(
            long userId,
            String name,
            float weightKg,
            float toleranceKg) {
        UserProfile profile =
                new UserProfile(
                        userId,
                        name);

        profile.enabled =
                true;

        profile.referenceWeightKg =
                weightKg;

        profile.toleranceKg =
                toleranceKg;

        return profile;
    }

    private static HouseholdProfile householdProfile(
            String profileId,
            String name,
            String ownerDeviceId,
            float weightKg,
            float toleranceKg) {
        return new HouseholdProfile(
                profileId,
                name,
                ownerDeviceId,
                weightKg,
                toleranceKg,
                true,
                1L);
    }
}
