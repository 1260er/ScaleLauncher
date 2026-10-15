package de.pritcloud.scalelauncher;

/** Pure decision logic; does not operate Bluetooth or schedule reconnects. */
final class S400ConnectingRecoveryPolicy {
    private S400ConnectingRecoveryPolicy() {}

    static boolean shouldRecover(
            boolean collectorPresent,
            boolean connecting,
            long connectingAgeMs,
            long firstSightingAgeMs,
            long lastSightingAgeMs,
            long lastRecoveryAgeMs,
            long minimumConnectingMs,
            long sightingGraceMs,
            long sightingWindowMs,
            long recoveryCooldownMs) {

        if (collectorPresent || !connecting) {
            return false;
        }

        if (connectingAgeMs < 0
                || firstSightingAgeMs < 0
                || lastSightingAgeMs < 0
                || lastRecoveryAgeMs < 0
                || minimumConnectingMs < 0
                || sightingGraceMs < 0
                || sightingWindowMs < sightingGraceMs
                || recoveryCooldownMs < 0) {
            return false;
        }

        return connectingAgeMs >= minimumConnectingMs
                && firstSightingAgeMs <= connectingAgeMs
                && lastSightingAgeMs <= firstSightingAgeMs
                && firstSightingAgeMs >= sightingGraceMs
                && lastSightingAgeMs <= sightingWindowMs
                && lastRecoveryAgeMs >= recoveryCooldownMs;
    }
}
