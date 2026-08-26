package de.pritcloud.scalelauncher;

final class MeasurementRoutingPolicy {
    private MeasurementRoutingPolicy() {}

    static boolean shouldCreateHouseholdAmbiguousPending(
            UserMatcher.Status localStatus,
            HouseholdMeasurementRouter.Status householdStatus) {
        return localStatus != null
                && householdStatus
                == HouseholdMeasurementRouter.Status.AMBIGUOUS
                && localStatus
                != UserMatcher.Status.NO_MATCH;
    }

    static boolean shouldAutoResolveSingleRemainingCandidate(
            boolean manualRescue,
            int remainingCandidateCount) {
        return !manualRescue
                && remainingCandidateCount == 1;
    }
}
