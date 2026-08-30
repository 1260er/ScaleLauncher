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

    private static PeerOutboxStore.Item item(
            String messageId,
            int index) {
        return new PeerOutboxStore.Item(
                messageId,
                PEER_ID,
                PeerOutboxStore.KIND_MEASUREMENT,
                "measurement-" + index,
                "{\"test\":" + index + "}",
                1_700_000_000_000L + index);
    }
}
