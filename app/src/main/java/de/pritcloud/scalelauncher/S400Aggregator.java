package de.pritcloud.scalelauncher;

/**
 * Combines the two BLE advertisements emitted by the S400 for one weighing.
 * Based on openScale's GPLv3 S400Aggregator implementation.
 */
final class S400Aggregator {
    static final long SESSION_TIMEOUT_MS = 10_000L;
    private static final float DEDUP_WEIGHT_TOLERANCE_KG = 0.05f;
    private static final float DEDUP_IMPEDANCE_TOLERANCE_OHM = 1.0f;

    enum Status { PENDING, FINALIZED, DUPLICATE }

    static final class Outcome {
        final Status status;
        final Finalized finalized;

        private Outcome(Status status, Finalized finalized) {
            this.status = status;
            this.finalized = finalized;
        }

        static Outcome pending() { return new Outcome(Status.PENDING, null); }
        static Outcome duplicate() { return new Outcome(Status.DUPLICATE, null); }
        static Outcome finalized(Finalized value) { return new Outcome(Status.FINALIZED, value); }
    }

    static final class Finalized {
        final float weightKg;
        final float impedanceHigh;
        final Float impedanceLow;
        final Integer heartRate;
        final boolean timedOut;

        Finalized(float weightKg, float impedanceHigh, Float impedanceLow,
                  Integer heartRate, boolean timedOut) {
            this.weightKg = weightKg;
            this.impedanceHigh = impedanceHigh;
            this.impedanceLow = impedanceLow;
            this.heartRate = heartRate;
            this.timedOut = timedOut;
        }
    }

    private static final class Session {
        Float weightKg;
        Float impedanceHigh;
        Float impedanceLow;
        Integer heartRate;
        long firstSeenAt;
    }

    private static final class Recent {
        final float weightKg;
        final float impedanceHigh;
        final long timeMs;

        Recent(float weightKg, float impedanceHigh, long timeMs) {
            this.weightKg = weightKg;
            this.impedanceHigh = impedanceHigh;
            this.timeMs = timeMs;
        }
    }

    private Session session;
    private Recent recent;

    Outcome ingest(S400Decryptor.Measurement packet, long nowMs) {
        if (session == null) {
            session = new Session();
            session.firstSeenAt = nowMs;
        }

        if (packet.weightKg > 0f) session.weightKg = packet.weightKg;
        if (packet.impedanceHigh != null) session.impedanceHigh = packet.impedanceHigh;
        if (packet.impedanceLow != null) session.impedanceLow = packet.impedanceLow;
        if (packet.heartRate != null) session.heartRate = packet.heartRate;

        if (session.weightKg == null || session.impedanceHigh == null) {
            return Outcome.pending();
        }
        if (session.impedanceLow == null) {
            return Outcome.pending();
        }
        return finalizeSession(nowMs, false);
    }

    Outcome finalizeTimedOut(long nowMs) {
        if (session == null) return Outcome.pending();
        if (nowMs - session.firstSeenAt < SESSION_TIMEOUT_MS) return Outcome.pending();
        if (session.weightKg == null || session.impedanceHigh == null) {
            session = null;
            return Outcome.pending();
        }
        return finalizeSession(nowMs, true);
    }

    long remainingTimeoutMs(long nowMs) {
        if (session == null) return SESSION_TIMEOUT_MS;
        return Math.max(0L, SESSION_TIMEOUT_MS - (nowMs - session.firstSeenAt));
    }

    private Outcome finalizeSession(long nowMs, boolean timedOut) {
        Session current = session;
        session = null;
        if (current == null || current.weightKg == null || current.impedanceHigh == null) {
            return Outcome.pending();
        }

        if (recent != null
                && Math.abs(recent.weightKg - current.weightKg) < DEDUP_WEIGHT_TOLERANCE_KG
                && Math.abs(recent.impedanceHigh - current.impedanceHigh) < DEDUP_IMPEDANCE_TOLERANCE_OHM
                && nowMs - recent.timeMs < SESSION_TIMEOUT_MS) {
            return Outcome.duplicate();
        }

        recent = new Recent(current.weightKg, current.impedanceHigh, nowMs);
        return Outcome.finalized(new Finalized(
                current.weightKg,
                current.impedanceHigh,
                current.impedanceLow,
                current.heartRate,
                timedOut));
    }

    void reset() {
        session = null;
        recent = null;
    }
}
