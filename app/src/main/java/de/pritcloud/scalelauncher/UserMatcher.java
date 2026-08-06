package de.pritcloud.scalelauncher;

import android.content.Context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class UserMatcher {
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

        return new Result(Status.AMBIGUOUS, null, candidates);
    }

    static String diagnosticSummary(Context context, Result result) {
        if (result.candidates.isEmpty()) {
            return context.getString(R.string.user_match_no_profiles);
        }
        List<String> parts = new ArrayList<>();
        for (Candidate candidate : result.candidates) {
            parts.add(context.getString(
                    R.string.user_match_candidate,
                    candidate.profile.name,
                    candidate.differenceKg));
        }
        return String.join(", ", parts);
    }
}
