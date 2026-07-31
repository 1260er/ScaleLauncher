package de.pritcloud.scalelauncher;

/**
 * Combines the two BLE advertisements emitted by the S400 for one weighing.
 * A measurement is accepted only when packet A and packet B are both complete.
 */
final class S400Aggregator {
    static final long SESSION_TIMEOUT_MS = 10_000L;
    private static final float DEDUP_WEIGHT_TOLERANCE_KG = 0.05f;
    private static final float DEDUP_IMPEDANCE_TOLERANCE_OHM = 1.0f;

    enum Status { PENDING, FINALIZED, DUPLICATE, INCOMPLETE }

    static final class Outcome {
        final Status status;
        final Finalized finalized;
        final String reason;

        private Outcome(Status status, Finalized finalized, String reason) {
            this.status = status;
            this.finalized = finalized;
            this.reason = reason;
        }

        static Outcome pending() { return new Outcome(Status.PENDING, null, ""); }
        static Outcome duplicate() { return new Outcome(Status.DUPLICATE, null, ""); }
        static Outcome finalized(Finalized value) {
            return new Outcome(Status.FINALIZED, value, "");
        }
        static Outcome incomplete(String reason) {
            return new Outcome(Status.INCOMPLETE, null, reason == null ? "Messdaten unvollständig" : reason);
        }
    }

    static final class Finalized {
        final float weightKg;
        final float impedanceHigh;
        final Float impedanceLow;
        final boolean timedOut;
        final long timestampMs;

        Finalized(float weightKg, float impedanceHigh, Float impedanceLow,
                  boolean timedOut, long timestampMs) {
            this.weightKg = weightKg;
            this.impedanceHigh = impedanceHigh;
            this.impedanceLow = impedanceLow;
            this.timedOut = timedOut;
            this.timestampMs = timestampMs;
        }

        boolean isComplete() {
            return Float.isFinite(weightKg) && weightKg > 0f
                    && Float.isFinite(impedanceHigh) && impedanceHigh > 0f
                    && impedanceLow != null
                    && Float.isFinite(impedanceLow) && impedanceLow > 0f
                    && !timedOut;
        }
    }

    private static final class Session {
        Float weightKg;
        Float impedanceHigh;
        Float impedanceLow;
        long firstSeenAt;
    }

    private static final class Recent {
        final float weightKg;
        final float impedanceHigh;
        final float impedanceLow;
        final long timeMs;

        Recent(float weightKg, float impedanceHigh, float impedanceLow, long timeMs) {
            this.weightKg = weightKg;
            this.impedanceHigh = impedanceHigh;
            this.impedanceLow = impedanceLow;
            this.timeMs = timeMs;
        }
    }

    private Session session;
    private Recent recent;

    Outcome ingest(S400Decryptor.Measurement packet, long nowMs) {
        // The scale repeats both advertisements many times. After a complete
        // weighing, ignore the trailing copies so they cannot start a second,
        // apparently incomplete session and trigger a false error notification.
        if (isRecentDuplicatePacket(packet, nowMs)) return Outcome.duplicate();

        if (session == null) {
            session = new Session();
            session.firstSeenAt = nowMs;
        }

        if (packet.weightKg > 0f) session.weightKg = packet.weightKg;
        if (packet.impedanceHigh != null) session.impedanceHigh = packet.impedanceHigh;
        if (packet.impedanceLow != null) session.impedanceLow = packet.impedanceLow;

        if (session.weightKg == null
                || session.impedanceHigh == null
                || session.impedanceLow == null) {
            return Outcome.pending();
        }
        return finalizeSession(nowMs);
    }

    Outcome finalizeTimedOut(long nowMs) {
        if (session == null) return Outcome.pending();
        if (nowMs - session.firstSeenAt < SESSION_TIMEOUT_MS) return Outcome.pending();

        String reason = missingParts(session);
        session = null;
        return Outcome.incomplete(reason);
    }

    long remainingTimeoutMs(long nowMs) {
        if (session == null) return SESSION_TIMEOUT_MS;
        return Math.max(0L, SESSION_TIMEOUT_MS - (nowMs - session.firstSeenAt));
    }

    boolean hasPendingSession() {
        return session != null;
    }

    private Outcome finalizeSession(long nowMs) {
        Session current = session;
        session = null;
        if (current == null
                || current.weightKg == null
                || current.impedanceHigh == null
                || current.impedanceLow == null) {
            return Outcome.incomplete("Paket A oder Paket B fehlte");
        }

        if (recent != null
                && Math.abs(recent.weightKg - current.weightKg) < DEDUP_WEIGHT_TOLERANCE_KG
                && Math.abs(recent.impedanceHigh - current.impedanceHigh) < DEDUP_IMPEDANCE_TOLERANCE_OHM
                && nowMs - recent.timeMs < SESSION_TIMEOUT_MS) {
            return Outcome.duplicate();
        }

        recent = new Recent(
                current.weightKg,
                current.impedanceHigh,
                current.impedanceLow,
                nowMs);
        return Outcome.finalized(new Finalized(
                current.weightKg,
                current.impedanceHigh,
                current.impedanceLow,
                false,
                current.firstSeenAt));
    }

    private boolean isRecentDuplicatePacket(S400Decryptor.Measurement packet, long nowMs) {
        if (recent == null || nowMs - recent.timeMs >= SESSION_TIMEOUT_MS) return false;
        if (packet.isPacketA()) {
            return Math.abs(recent.weightKg - packet.weightKg) < DEDUP_WEIGHT_TOLERANCE_KG
                    && packet.impedanceHigh != null
                    && Math.abs(recent.impedanceHigh - packet.impedanceHigh)
                    < DEDUP_IMPEDANCE_TOLERANCE_OHM;
        }
        if (packet.isPacketB()) {
            return packet.impedanceLow != null
                    && Math.abs(recent.impedanceLow - packet.impedanceLow)
                    < DEDUP_IMPEDANCE_TOLERANCE_OHM;
        }
        return false;
    }

    private static String missingParts(Session value) {
        boolean missingA = value.weightKg == null || value.impedanceHigh == null;
        boolean missingB = value.impedanceLow == null;
        if (missingA && missingB) return "Paket A und Paket B wurden nicht vollständig empfangen";
        if (missingA) return "Paket A mit Gewicht und hoher Impedanz fehlte";
        if (missingB) return "Paket B mit niedriger Impedanz fehlte";
        return "Messdaten waren unvollständig";
    }

    void reset() {
        session = null;
        recent = null;
    }
}
