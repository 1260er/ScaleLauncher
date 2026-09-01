package de.pritcloud.scalelauncher;

final class PeerRetryPolicy {
    static final long BASE_DELAY_MS = 5_000L;
    static final long MAX_DELAY_MS = 60_000L;
    static final long JITTER_SPAN_MS = 4_000L;

    private PeerRetryPolicy() {}

    static long delayMs(
            int attempt,
            int deviceHash) {
        int safeAttempt =
                Math.max(
                        0,
                        attempt);

        int shift =
                Math.min(
                        safeAttempt,
                        4);

        long baseDelayMs =
                Math.min(
                        MAX_DELAY_MS,
                        BASE_DELAY_MS << shift);

        long offset =
                baseDelayMs >= MAX_DELAY_MS
                        ? 0L
                        : Math.floorMod(
                                deviceHash,
                                (int) JITTER_SPAN_MS);

        return Math.min(
                MAX_DELAY_MS,
                baseDelayMs + offset);
    }
}
