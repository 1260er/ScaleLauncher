package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class PeerOutboxStoreTest {
    private static final String PEER_ID =
            "11111111-1111-1111-1111-111111111111";

    @Test
    public void keepsItemsBeyondFormerThousandItemLimit() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        List<PeerOutboxStore.Item> initial =
                new ArrayList<>();

        for (int index = 0; index < 1000; index++) {
            initial.add(
                    item(
                            "message-" + index,
                            index));
        }

        PeerOutboxStore.save(
                prefs,
                initial);

        PeerOutboxStore.enqueue(
                prefs,
                item(
                        "message-1000",
                        1000),
                false);

        List<PeerOutboxStore.Item> items =
                PeerOutboxStore.load(
                        prefs);

        assertEquals(
                1001,
                items.size());

        assertEquals(
                "message-0",
                items.get(0).messageId);

        assertEquals(
                "message-1000",
                items.get(1000).messageId);
    }

    @Test
    public void removePeerRemovesOnlyItsOutboxItems() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        String otherPeerId =
                "22222222-2222-2222-2222-222222222222";

        List<PeerOutboxStore.Item> initial =
                new ArrayList<>();

        initial.add(
                item(
                        "peer-one-message-1",
                        PEER_ID,
                        1));

        initial.add(
                item(
                        "peer-two-message",
                        otherPeerId,
                        2));

        initial.add(
                item(
                        "peer-one-message-2",
                        PEER_ID,
                        3));

        PeerOutboxStore.save(
                prefs,
                initial);

        assertEquals(
                2,
                PeerOutboxStore.removePeer(
                        prefs,
                        PEER_ID));

        List<PeerOutboxStore.Item> remaining =
                PeerOutboxStore.load(
                        prefs);

        assertEquals(
                1,
                remaining.size());

        assertEquals(
                otherPeerId,
                remaining.get(0).peerDeviceId);

        assertEquals(
                "peer-two-message",
                remaining.get(0).messageId);
    }

    private static PeerOutboxStore.Item item(
            String messageId,
            int index) {
        return item(
                messageId,
                PEER_ID,
                index);
    }

    private static PeerOutboxStore.Item item(
            String messageId,
            String peerDeviceId,
            int index) {
        return new PeerOutboxStore.Item(
                messageId,
                peerDeviceId,
                PeerOutboxStore.KIND_MEASUREMENT,
                "measurement-" + index,
                "{\"test\":" + index + "}",
                1_700_000_000_000L + index);
    }
}
