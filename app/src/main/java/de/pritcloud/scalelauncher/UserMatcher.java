package de.pritcloud.scalelauncher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class UserMatcher {
    static final float MINIMUM_LEAD_KG = 1.0f;

    enum Status { MATCHED, AMBIGUOUS, NO_MATCH }

    static final class Candidate {
        final UserProfile profile;
        final float differenceKg;

        Candidate(UserProfile profile, float differenceKg) {
            this.profile = profile;
            this.differenceKg = differenceKg;
        }
    }

    static final class Result {
        final Status status;
        final UserProfile profile;
        final List<Candidate> candidates;

        Result(Status status, UserProfile profile, List<Candidate> candidates) {
            this.status = status;
            this.profile = profile;
            this.candidates = candidates;
        }
    }

    private UserMatcher() {}

    static Result match(List<UserProfile> profiles, float measuredWeightKg) {
        List<Candidate> candidates = new ArrayList<>();
        for (UserProfile profile : profiles) {
            if (!profile.hasValidMatchingData()) continue;
            float difference = Math.abs(measuredWeightKg - profile.referenceWeightKg);
            if (difference <= profile.toleranceKg) {
                candidates.add(new Candidate(profile, difference));
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate -> candidate.differenceKg));
        if (candidates.isEmpty()) return new Result(Status.NO_MATCH, null, candidates);
        if (candidates.size() == 1) {
            return new Result(Status.MATCHED, candidates.get(0).profile, candidates);
        }

        Candidate first = candidates.get(0);
        Candidate second = candidates.get(1);
        if (second.differenceKg - first.differenceKg >= MINIMUM_LEAD_KG) {
            return new Result(Status.MATCHED, first.profile, candidates);
        }
        return new Result(Status.AMBIGUOUS, null, candidates);
    }

    static String diagnosticSummary(Result result) {
        if (result.candidates.isEmpty()) return "keine passenden Profile";
        List<String> parts = new ArrayList<>();
        for (Candidate candidate : result.candidates) {
            parts.add(candidate.profile.name + " Δ"
                    + String.format(java.util.Locale.GERMANY, "%.1f kg", candidate.differenceKg));
        }
        return String.join(", ", parts);
    }
}
