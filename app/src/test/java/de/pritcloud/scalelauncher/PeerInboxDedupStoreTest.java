package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PeerInboxDedupStoreTest {
    private static final String PEER_ID =
            "11111111-1111-1111-1111-111111111111";

    @Test
    public void keepsItemsBeyondFormerThousandItemLimit() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        for (int index = 0; index <= 1000; index++) {
            PeerInboxDedupStore.mark(
                    prefs,
                    PEER_ID,
                    "message-" + index);
        }

        assertTrue(
                PeerInboxDedupStore.contains(
                        prefs,
                        PEER_ID,
                        "message-0"));

        assertTrue(
                PeerInboxDedupStore.contains(
                        prefs,
                        PEER_ID,
                        "message-1000"));
    }
}
