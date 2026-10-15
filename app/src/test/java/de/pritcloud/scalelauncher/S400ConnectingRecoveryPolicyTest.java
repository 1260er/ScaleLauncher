package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class S400ConnectingRecoveryPolicyTest {
    // Illustrative test thresholds, not production settings.
    private static final long MIN_WAIT = 60_000L;
    private static final long GRACE = 5_000L;
    private static final long WINDOW = 15_000L;
    private static final long COOLDOWN = 300_000L;

    private boolean decide(
            boolean collector,
            boolean connecting,
            long connectingAge,
            long seenAge,
            long recoveryAge) {
        return S400ConnectingRecoveryPolicy.shouldRecover(
                collector, connecting,
                connectingAge, seenAge, seenAge, recoveryAge,
                MIN_WAIT, GRACE, WINDOW, COOLDOWN);
    }

    @Test
    public void eligibleAttemptMayRecover() {
        assertTrue(decide(false, true, 90_000L,
                8_000L, Long.MAX_VALUE));
    }

    @Test
    public void availableCollectorBlocksRecovery() {
        assertFalse(decide(true, true, 90_000L,
                8_000L, Long.MAX_VALUE));
    }

    @Test
    public void readyOrOtherStateBlocksRecovery() {
        assertFalse(decide(false, false, 90_000L,
                8_000L, Long.MAX_VALUE));
    }

    @Test
    public void normalInitialWaitingIsProtected() {
        assertFalse(decide(false, true, 20_000L,
                8_000L, Long.MAX_VALUE));
    }

    @Test
    public void missingOrStaleSightingBlocksRecovery() {
        assertFalse(decide(false, true, 90_000L,
                -1L, Long.MAX_VALUE));
        assertFalse(decide(false, true, 90_000L,
                20_000L, Long.MAX_VALUE));
        assertFalse(decide(false, true, 90_000L,
                2_000L, Long.MAX_VALUE));
    }

    @Test
    public void sightingBeforeAttemptBlocksRecovery() {
        assertFalse(decide(false, true, 60_000L,
                70_000L, Long.MAX_VALUE));
    }

    @Test
    public void cooldownPreventsRepeatedRecovery() {
        assertFalse(decide(false, true, 90_000L,
                8_000L, 20_000L));
        assertTrue(decide(false, true, 90_000L,
                8_000L, COOLDOWN));
    }

    @Test
    public void repeatedAdvertisingDoesNotResetGrace() {
        assertTrue(S400ConnectingRecoveryPolicy.shouldRecover(
                false, true, 90_000L,
                12_000L, 1_000L, Long.MAX_VALUE,
                MIN_WAIT, GRACE, WINDOW, COOLDOWN));
    }

    @Test
    public void invalidSightingOrderBlocksRecovery() {
        assertFalse(S400ConnectingRecoveryPolicy.shouldRecover(
                false, true, 90_000L,
                2_000L, 8_000L, Long.MAX_VALUE,
                MIN_WAIT, GRACE, WINDOW, COOLDOWN));
    }

    @Test
    public void staleLastSightingBlocksRecovery() {
        assertFalse(S400ConnectingRecoveryPolicy.shouldRecover(
                false, true, 90_000L,
                30_000L, 20_000L, Long.MAX_VALUE,
                MIN_WAIT, GRACE, WINDOW, COOLDOWN));
    }

    @Test
    public void firstFreshProbeResultIsEligible() {
        assertTrue(S400ConnectingRecoveryPolicy.shouldRecover(
                false, true, 660_000L,
                100L, 100L, Long.MAX_VALUE,
                600_000L, 0L, 5_000L, 1_800_000L));
    }

    @Test
    public void freshProbeCannotOverrideRemoteCollector() {
        assertFalse(S400ConnectingRecoveryPolicy.shouldRecover(
                true, true, 660_000L,
                100L, 100L, Long.MAX_VALUE,
                600_000L, 0L, 5_000L, 1_800_000L));
    }

    @Test
    public void oldProbeResultCannotTriggerRecovery() {
        assertFalse(S400ConnectingRecoveryPolicy.shouldRecover(
                false, true, 660_000L,
                6_000L, 6_000L, Long.MAX_VALUE,
                600_000L, 0L, 5_000L, 1_800_000L));
    }

    @Test
    public void freshProbeRespectsRecoveryCooldown() {
        assertFalse(S400ConnectingRecoveryPolicy.shouldRecover(
                false, true, 660_000L,
                100L, 100L, 60_000L,
                600_000L, 0L, 5_000L, 1_800_000L));
    }

    @Test
    public void unknownAgeOrInvalidThresholdBlocksRecovery() {
        assertFalse(decide(false, true, -1L,
                8_000L, Long.MAX_VALUE));
        assertFalse(S400ConnectingRecoveryPolicy.shouldRecover(
                false, true, 90_000L, 8_000L, 8_000L,
                Long.MAX_VALUE, MIN_WAIT,
                WINDOW + 1L, WINDOW, COOLDOWN));
    }
}
