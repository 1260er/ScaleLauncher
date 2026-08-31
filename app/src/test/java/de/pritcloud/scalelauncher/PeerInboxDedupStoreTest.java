package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test
    public void removePeerRemovesOnlyItsDedupItems() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        String otherPeerId =
                "22222222-2222-2222-2222-222222222222";

        PeerInboxDedupStore.mark(
                prefs,
                PEER_ID,
                "peer-one-message-1");

        PeerInboxDedupStore.mark(
                prefs,
                otherPeerId,
                "peer-two-message");

        PeerInboxDedupStore.mark(
                prefs,
                PEER_ID,
                "peer-one-message-2");

        assertEquals(
                2,
                PeerInboxDedupStore.removePeer(
                        prefs,
                        PEER_ID));

        assertTrue(
                PeerInboxDedupStore.contains(
                        prefs,
                        otherPeerId,
                        "peer-two-message"));
    }



    @Test
    public void sameMessageIdIsTrackedSeparatelyPerPeer() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        String otherPeerId =
                "22222222-2222-2222-2222-222222222222";

        String sharedMessageId =
                "shared-message-id";

        PeerInboxDedupStore.mark(
                prefs,
                PEER_ID,
                sharedMessageId);

        assertTrue(
                PeerInboxDedupStore.contains(
                        prefs,
                        PEER_ID,
                        sharedMessageId));

        assertFalse(
                PeerInboxDedupStore.contains(
                        prefs,
                        otherPeerId,
                        sharedMessageId));

        PeerInboxDedupStore.mark(
                prefs,
                otherPeerId,
                sharedMessageId);

        assertTrue(
                PeerInboxDedupStore.contains(
                        prefs,
                        PEER_ID,
                        sharedMessageId));

        assertTrue(
                PeerInboxDedupStore.contains(
                        prefs,
                        otherPeerId,
                        sharedMessageId));

        assertEquals(
                1,
                PeerInboxDedupStore.removePeer(
                        prefs,
                        PEER_ID));

        assertTrue(
                PeerInboxDedupStore.contains(
                        prefs,
                        otherPeerId,
                        sharedMessageId));
    }



    @Test
    public void repeatedMessageFromSamePeerIsStoredOnlyOnce() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PeerInboxDedupStore.mark(
                prefs,
                PEER_ID,
                "repeated-message");

        PeerInboxDedupStore.mark(
                prefs,
                PEER_ID,
                "repeated-message");

        assertEquals(
                1,
                PeerInboxDedupStore.removePeer(
                        prefs,
                        PEER_ID));
    }


}
