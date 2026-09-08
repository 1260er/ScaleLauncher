package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PeerAdvertisingRecoveryPolicyTest {
    @Test
    public void leaseExpiresWithoutRecentPeerProof() {
        long started = 1_000L;

        assertFalse(
                PeerAdvertisingRecoveryPolicy.shouldRenew(
                        started
                                + PeerAdvertisingRecoveryPolicy.LEASE_MS
                                - 1L,
                        started,
                        0L));

        assertTrue(
                PeerAdvertisingRecoveryPolicy.shouldRenew(
                        started
                                + PeerAdvertisingRecoveryPolicy.LEASE_MS,
                        started,
                        0L));
    }

    @Test
    public void recentPeerMessageExtendsLease() {
        long started = 1_000L;
        long proof =
                started
                        + PeerAdvertisingRecoveryPolicy.LEASE_MS
                        / 2L;

        assertFalse(
                PeerAdvertisingRecoveryPolicy.shouldRenew(
                        started
                                + PeerAdvertisingRecoveryPolicy.LEASE_MS,
                        started,
                        proof));

        assertTrue(
                PeerAdvertisingRecoveryPolicy.shouldRenew(
                        proof
                                + PeerAdvertisingRecoveryPolicy.LEASE_MS,
                        started,
                        proof));
    }

    @Test
    public void missingSuccessfulStartNeverRenews() {
        assertFalse(
                PeerAdvertisingRecoveryPolicy.shouldRenew(
                        PeerAdvertisingRecoveryPolicy.LEASE_MS,
                        0L,
                        0L));
    }
}
