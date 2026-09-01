package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PeerRetryPolicyTest {
    @Test
    public void repeatedFailuresBackOffToSixtySeconds() {
        assertEquals(5_000L, PeerRetryPolicy.delayMs(0, 0));
        assertEquals(10_000L, PeerRetryPolicy.delayMs(1, 0));
        assertEquals(20_000L, PeerRetryPolicy.delayMs(2, 0));
        assertEquals(40_000L, PeerRetryPolicy.delayMs(3, 0));
        assertEquals(60_000L, PeerRetryPolicy.delayMs(4, 0));
        assertEquals(60_000L, PeerRetryPolicy.delayMs(20, 0));
    }

    @Test
    public void jitterNeverExceedsMaximumDelay() {
        long first =
                PeerRetryPolicy.delayMs(
                        0,
                        Integer.MAX_VALUE);

        assertTrue(first >= 5_000L);
        assertTrue(first < 9_000L);

        assertEquals(
                60_000L,
                PeerRetryPolicy.delayMs(
                        4,
                        Integer.MAX_VALUE));
    }
}
