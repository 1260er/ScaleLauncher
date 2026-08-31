package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ServiceStateTest {
    @Test
    public void runningStateUsesUptimeForStaleness() {
        long nowUptimeMs =
                100_000L;

        ServiceState.Snapshot recent =
                snapshot(
                        ServiceState.Mode.RUNNING,
                        nowUptimeMs - ServiceState.STALE_AFTER_MS);

        assertFalse(
                recent.isStaleAt(
                        nowUptimeMs));

        ServiceState.Snapshot stale =
                snapshot(
                        ServiceState.Mode.RUNNING,
                        nowUptimeMs - ServiceState.STALE_AFTER_MS - 1L);

        assertTrue(
                stale.isStaleAt(
                        nowUptimeMs));

        assertTrue(
                snapshot(
                        ServiceState.Mode.RUNNING,
                        0L)
                        .isStaleAt(nowUptimeMs));

        assertTrue(
                snapshot(
                        ServiceState.Mode.RUNNING,
                        nowUptimeMs + 1L)
                        .isStaleAt(nowUptimeMs));
    }

    @Test
    public void inactiveStatesAreNeverMarkedStale() {
        assertFalse(
                snapshot(
                        ServiceState.Mode.STOPPED,
                        0L)
                        .isStaleAt(100_000L));

        assertFalse(
                snapshot(
                        ServiceState.Mode.ERROR,
                        0L)
                        .isStaleAt(100_000L));
    }

    private static ServiceState.Snapshot snapshot(
            ServiceState.Mode mode,
            long heartbeatMs) {
        return new ServiceState.Snapshot(
                mode,
                "",
                heartbeatMs,
                false,
                ServiceState.CollectorSource.NONE,
                0L,
                0L,
                0L);
    }
}
