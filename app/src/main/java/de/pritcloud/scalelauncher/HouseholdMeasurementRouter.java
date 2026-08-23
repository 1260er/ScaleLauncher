package de.pritcloud.scalelauncher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class HouseholdMeasurementRouter {
    enum Status {
        UNIQUE,
        AMBIGUOUS,
        NO_MATCH
    }

    static final class Candidate {
        final HouseholdProfile profile;
        final float differenceKg;

        Candidate(
                HouseholdProfile profile,
                float differenceKg) {
            this.profile = profile;
            this.differenceKg = differenceKg;
        }
    }

    static final class Result {
        final Status status;
        final HouseholdProfile uniqueProfile;
        final List<Candidate> candidates;
        final List<String> targetDeviceIds;

        Result(
                Status status,
                HouseholdProfile uniqueProfile,
                List<Candidate> candidates,
                List<String> targetDeviceIds) {
            this.status = status;
            this.uniqueProfile = uniqueProfile;
            this.candidates = candidates;
            this.targetDeviceIds = targetDeviceIds;
        }

        boolean includesDevice(
                String deviceId) {
            return targetDeviceIds.contains(
                    deviceId);
        }

        List<String> profileIdsForDevice(
                String deviceId) {
            List<String> result =
                    new ArrayList<>();

            if (!PeerTrustStore.isValidDeviceId(
                    deviceId)) {
                return result;
            }

            for (Candidate candidate :
                    candidates) {
                if (deviceId.equals(
                        candidate.profile.ownerDeviceId)) {
                    result.add(
                            candidate.profile.profileId);
                }
            }

            return result;
        }
    }

    private HouseholdMeasurementRouter() {}

    static Result match(
            List<HouseholdProfile> profiles,
            float measuredWeightKg) {
        List<Candidate> candidates =
                new ArrayList<>();

        if (!Float.isFinite(measuredWeightKg)
                || measuredWeightKg <= 0f) {
            return new Result(
                    Status.NO_MATCH,
                    null,
                    candidates,
                    List.of());
        }

        if (profiles != null) {
            for (HouseholdProfile profile :
                    profiles) {
                if (profile == null
                        || !profile.active
                        || !profile.isValid()) {
                    continue;
                }

                float difference =
                        Math.abs(
                                measuredWeightKg
                                        - profile.referenceWeightKg);

                if (difference
                        <= profile.toleranceKg) {
                    candidates.add(
                            new Candidate(
                                    profile,
                                    difference));
                }
            }
        }

        candidates.sort(
                Comparator.comparingDouble(
                        candidate ->
                                candidate.differenceKg));

        if (candidates.isEmpty()) {
            return new Result(
                    Status.NO_MATCH,
                    null,
                    candidates,
                    List.of());
        }

        Set<String> targets =
                new LinkedHashSet<>();

        for (Candidate candidate :
                candidates) {
            targets.add(
                    candidate.profile.ownerDeviceId);
        }

        if (candidates.size() == 1) {
            return new Result(
                    Status.UNIQUE,
                    candidates.get(0).profile,
                    candidates,
                    new ArrayList<>(
                            targets));
        }

        return new Result(
                Status.AMBIGUOUS,
                null,
                candidates,
                new ArrayList<>(
                        targets));
    }
}
