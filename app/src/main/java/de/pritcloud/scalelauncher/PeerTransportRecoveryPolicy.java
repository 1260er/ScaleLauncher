package de.pritcloud.scalelauncher;

final class PeerTransportRecoveryPolicy {
    static final int FAILURE_THRESHOLD =
            3;

    static final long COOLDOWN_MS =
            5L * 60_000L;

    private PeerTransportRecoveryPolicy() {}

    static boolean isCriticalKind(
            String kind) {
        return PeerOutboxStore.KIND_MEASUREMENT.equals(
                        kind)
                || PeerOutboxStore.KIND_CLAIM.equals(
                        kind)
                || PeerOutboxStore.KIND_DECISION.equals(
                        kind)
                || PeerOutboxStore.KIND_CLOSED.equals(
                        kind);
    }

    static boolean shouldRestart(
            int consecutiveCriticalFailures,
            long nowElapsedMs,
            long lastRestartElapsedMs) {
        if (consecutiveCriticalFailures
                < FAILURE_THRESHOLD) {
            return false;
        }

        if (lastRestartElapsedMs <= 0L) {
            return true;
        }

        return nowElapsedMs >= lastRestartElapsedMs
                && nowElapsedMs - lastRestartElapsedMs
                        >= COOLDOWN_MS;
    }
}
