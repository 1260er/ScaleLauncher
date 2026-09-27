package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PeerTransportRecoveryPolicyTest {
    @Test
    public void onlyMeasurementFlowMessagesAreCritical() {
        assertTrue(
                PeerTransportRecoveryPolicy.isCriticalKind(
                        PeerOutboxStore.KIND_MEASUREMENT));
        assertTrue(
                PeerTransportRecoveryPolicy.isCriticalKind(
                        PeerOutboxStore.KIND_CLAIM));
        assertTrue(
                PeerTransportRecoveryPolicy.isCriticalKind(
                        PeerOutboxStore.KIND_DECISION));
        assertTrue(
                PeerTransportRecoveryPolicy.isCriticalKind(
                        PeerOutboxStore.KIND_CLOSED));

        assertFalse(
                PeerTransportRecoveryPolicy.isCriticalKind(
                        PeerOutboxStore.KIND_PROFILE));
        assertFalse(
                PeerTransportRecoveryPolicy.isCriticalKind(
                        PeerOutboxStore.KIND_PROFILE_MANIFEST));
        assertFalse(
                PeerTransportRecoveryPolicy.isCriticalKind(
                        PeerOutboxStore.KIND_COLLECTOR_STATUS));
    }

    @Test
    public void thirdCriticalFailureAllowsRestart() {
        assertFalse(
                PeerTransportRecoveryPolicy.shouldRestart(
                        2,
                        100_000L,
                        0L));

        assertTrue(
                PeerTransportRecoveryPolicy.shouldRestart(
                        3,
                        100_000L,
                        0L));
    }

    @Test
    public void restartCooldownPreventsLoop() {
        long lastRestart =
                100_000L;

        assertFalse(
                PeerTransportRecoveryPolicy.shouldRestart(
                        3,
                        lastRestart
                                + PeerTransportRecoveryPolicy.COOLDOWN_MS
                                - 1L,
                        lastRestart));

        assertTrue(
                PeerTransportRecoveryPolicy.shouldRestart(
                        3,
                        lastRestart
                                + PeerTransportRecoveryPolicy.COOLDOWN_MS,
                        lastRestart));
    }

    @Test
    public void clockBeforeLastRestartNeverRestarts() {
        assertFalse(
                PeerTransportRecoveryPolicy.shouldRestart(
                        3,
                        99_999L,
                        100_000L));
    }
}
