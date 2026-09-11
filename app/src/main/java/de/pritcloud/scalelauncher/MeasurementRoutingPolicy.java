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

    static boolean shouldCreateRemoteOnlyHouseholdAmbiguousPending(
            UserMatcher.Status localStatus,
            HouseholdMeasurementRouter.Result householdMatch,
            String localDeviceId) {
        if (localStatus != UserMatcher.Status.NO_MATCH
                || householdMatch == null
                || householdMatch.status
                        != HouseholdMeasurementRouter.Status.AMBIGUOUS
                || localDeviceId == null
                || localDeviceId.isBlank()) {
            return false;
        }

        String remoteOwnerDeviceId = null;
        int candidateCount = 0;

        for (HouseholdMeasurementRouter.Candidate candidate :
                householdMatch.candidates) {
            if (candidate == null
                    || candidate.profile == null
                    || candidate.profile.ownerDeviceId == null
                    || candidate.profile.ownerDeviceId.isBlank()
                    || localDeviceId.equals(
                            candidate.profile.ownerDeviceId)) {
                return false;
            }

            if (remoteOwnerDeviceId == null) {
                remoteOwnerDeviceId =
                        candidate.profile.ownerDeviceId;
            } else if (!remoteOwnerDeviceId.equals(
                    candidate.profile.ownerDeviceId)) {
                return false;
            }

            candidateCount++;
        }

        return candidateCount >= 2;
    }

    static boolean shouldAutoResolveSingleRemainingCandidate(
            boolean manualRescue,
            int remainingCandidateCount) {
        return !manualRescue
                && remainingCandidateCount == 1;
    }
}
