package de.pritcloud.scalelauncher;

final class PeerVisibilityHealthPolicy {
    static final long STALE_MS =
            2L * 60_000L;

    private PeerVisibilityHealthPolicy() {}

    static boolean shouldRecover(
            long nowElapsedMs,
            long lastMatchedPresenceElapsedMs) {
        return lastMatchedPresenceElapsedMs > 0L
                && nowElapsedMs >= lastMatchedPresenceElapsedMs
                && nowElapsedMs - lastMatchedPresenceElapsedMs
                        >= STALE_MS;
    }
}
