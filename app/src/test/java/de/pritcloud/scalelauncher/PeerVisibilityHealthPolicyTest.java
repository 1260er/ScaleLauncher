package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PeerVisibilityHealthPolicyTest {
    @Test
    public void neverRecoversWithoutPriorMatchedPresence() {
        assertFalse(
                PeerVisibilityHealthPolicy.shouldRecover(
                        PeerVisibilityHealthPolicy.STALE_MS,
                        0L));
    }

    @Test
    public void matchedPresenceMustBecomeStale() {
        long presence =
                1_000L;

        assertFalse(
                PeerVisibilityHealthPolicy.shouldRecover(
                        presence
                                + PeerVisibilityHealthPolicy.STALE_MS
                                - 1L,
                        presence));

        assertTrue(
                PeerVisibilityHealthPolicy.shouldRecover(
                        presence
                                + PeerVisibilityHealthPolicy.STALE_MS,
                        presence));
    }

    @Test
    public void clockBeforePresenceNeverRecovers() {
        assertFalse(
                PeerVisibilityHealthPolicy.shouldRecover(
                        1_000L,
                        2_000L));
    }
}
