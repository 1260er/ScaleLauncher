package de.pritcloud.scalelauncher;

final class PeerAdvertisingRecoveryPolicy {
    static final long LEASE_MS =
            60L * 60_000L;

    private PeerAdvertisingRecoveryPolicy() {}

    static boolean shouldRenew(
            long nowElapsedMs,
            long startedElapsedMs,
            long lastProofElapsedMs) {
        long referenceElapsedMs =
                Math.max(
                        startedElapsedMs,
                        lastProofElapsedMs);

        return referenceElapsedMs > 0L
                && nowElapsedMs >= referenceElapsedMs
                && nowElapsedMs - referenceElapsedMs
                >= LEASE_MS;
    }
}
