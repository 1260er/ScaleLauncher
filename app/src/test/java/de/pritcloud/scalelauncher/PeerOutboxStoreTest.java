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


    @Test
    public void coalesceReplacesMatchingOutboxItem() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PeerOutboxStore.enqueue(
                prefs,
                new PeerOutboxStore.Item(
                        "message-old",
                        PEER_ID,
                        PeerOutboxStore.KIND_MEASUREMENT,
                        "measurement-1",
                        "version-1",
                        1_700_000_000_000L),
                true);

        PeerOutboxStore.enqueue(
                prefs,
                new PeerOutboxStore.Item(
                        "message-new",
                        PEER_ID,
                        PeerOutboxStore.KIND_MEASUREMENT,
                        "measurement-1",
                        "version-2",
                        1_700_000_000_001L),
                true);

        List<PeerOutboxStore.Item> stored =
                PeerOutboxStore.load(
                        prefs);

        assertEquals(
                1,
                stored.size());

        assertEquals(
                "message-new",
                stored.get(0).messageId);

        assertEquals(
                "version-2",
                stored.get(0).payload);
    }

    @Test
    public void removeMeasurementRemovesOnlyMatchingMeasurementItems() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        List<PeerOutboxStore.Item> initial =
                new ArrayList<>();

        initial.add(
                new PeerOutboxStore.Item(
                        "measurement-message",
                        PEER_ID,
                        PeerOutboxStore.KIND_MEASUREMENT,
                        "measurement-1",
                        "test-1",
                        1_700_000_000_000L));

        initial.add(
                new PeerOutboxStore.Item(
                        "decision-message",
                        PEER_ID,
                        PeerOutboxStore.KIND_DECISION,
                        "measurement-1:decision",
                        "test-2",
                        1_700_000_000_001L));

        initial.add(
                new PeerOutboxStore.Item(
                        "similar-measurement",
                        PEER_ID,
                        PeerOutboxStore.KIND_MEASUREMENT,
                        "measurement-10",
                        "test-3",
                        1_700_000_000_002L));

        initial.add(
                new PeerOutboxStore.Item(
                        "other-measurement",
                        PEER_ID,
                        PeerOutboxStore.KIND_MEASUREMENT,
                        "measurement-2",
                        "test-4",
                        1_700_000_000_003L));

        PeerOutboxStore.save(
                prefs,
                initial);

        assertEquals(
                2,
                PeerOutboxStore.removeMeasurement(
                        prefs,
                        "measurement-1"));

        List<PeerOutboxStore.Item> remaining =
                PeerOutboxStore.load(
                        prefs);

        assertEquals(
                2,
                remaining.size());

        assertEquals(
                "similar-measurement",
                remaining.get(0).messageId);

        assertEquals(
                "other-measurement",
                remaining.get(1).messageId);
    }



    @Test
    public void sameMessageIdIsTrackedSeparatelyPerPeer() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        String otherPeerId =
                "22222222-2222-2222-2222-222222222222";

        String sharedMessageId =
                "shared-message-id";

        PeerOutboxStore.enqueue(
                prefs,
                new PeerOutboxStore.Item(
                        sharedMessageId,
                        PEER_ID,
                        PeerOutboxStore.KIND_MEASUREMENT,
                        "measurement-1",
                        "peer-one",
                        1_700_000_000_000L),
                false);

        PeerOutboxStore.enqueue(
                prefs,
                new PeerOutboxStore.Item(
                        sharedMessageId,
                        otherPeerId,
                        PeerOutboxStore.KIND_MEASUREMENT,
                        "measurement-2",
                        "peer-two",
                        1_700_000_000_001L),
                false);

        List<PeerOutboxStore.Item> stored =
                PeerOutboxStore.load(
                        prefs);

        assertEquals(
                2,
                stored.size());

        assertEquals(
                PEER_ID,
                stored.get(0).peerDeviceId);

        assertEquals(
                otherPeerId,
                stored.get(1).peerDeviceId);
    }



    @Test
    public void repeatedMessageIdForSamePeerIsStoredOnlyOnce() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PeerOutboxStore.enqueue(
                prefs,
                new PeerOutboxStore.Item(
                        "repeated-message",
                        PEER_ID,
                        PeerOutboxStore.KIND_MEASUREMENT,
                        "measurement-1",
                        "first",
                        1_700_000_000_000L),
                false);

        PeerOutboxStore.enqueue(
                prefs,
                new PeerOutboxStore.Item(
                        "repeated-message",
                        PEER_ID,
                        PeerOutboxStore.KIND_MEASUREMENT,
                        "measurement-2",
                        "second",
                        1_700_000_000_001L),
                false);

        List<PeerOutboxStore.Item> stored =
                PeerOutboxStore.load(
                        prefs);

        assertEquals(
                1,
                stored.size());

        assertEquals(
                "measurement-1",
                stored.get(0).dedupKey);
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
