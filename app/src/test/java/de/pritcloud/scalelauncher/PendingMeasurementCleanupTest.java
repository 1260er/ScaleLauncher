package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class PendingMeasurementCleanupTest {

    private static final String PEER_ONE =
            "11111111-1111-4111-8111-111111111111";

    private static final String PEER_TWO =
            "22222222-2222-4222-8222-222222222222";

    private static final String PROFILE_ID =
            "33333333-3333-4333-8333-333333333333";

    @Test
    public void discardRemovesPendingAndQueuesClosedForPeers() {
        InMemorySharedPreferences pendingPrefs =
                new InMemorySharedPreferences();

        InMemorySharedPreferences outboxPrefs =
                new InMemorySharedPreferences();

        String measurementId =
                "cleanup-measurement";

        PendingMeasurementStore.add(
                pendingPrefs,
                new S400FinalMeasurement(
                        measurementId,
                        72.4f,
                        510.0f,
                        490.0f,
                        1_700_000_000_000L,
                        null),
                "test",
                List.of(PROFILE_ID));

        PendingMeasurementStore.recordClaimResponse(
                pendingPrefs,
                measurementId,
                PEER_ONE,
                List.of(PROFILE_ID));

        List<PeerOutboxStore.Item> initial =
                new ArrayList<>();

        initial.add(
                new PeerOutboxStore.Item(
                        "old-measurement-message",
                        PEER_ONE,
                        PeerOutboxStore.KIND_MEASUREMENT,
                        measurementId,
                        "{}",
                        1_700_000_000_001L));

        initial.add(
                new PeerOutboxStore.Item(
                        "old-decision-message",
                        PEER_TWO,
                        PeerOutboxStore.KIND_DECISION,
                        measurementId + ":" + PROFILE_ID,
                        "{}",
                        1_700_000_000_002L));

        initial.add(
                new PeerOutboxStore.Item(
                        "unrelated-message",
                        PEER_ONE,
                        PeerOutboxStore.KIND_MEASUREMENT,
                        "other-measurement",
                        "{}",
                        1_700_000_000_003L));

        PeerOutboxStore.save(
                outboxPrefs,
                initial);

        PendingMeasurementCleanup.Result result =
                PendingMeasurementCleanup.discardLocal(
                        pendingPrefs,
                        outboxPrefs,
                        List.of(
                                PEER_ONE,
                                PEER_TWO),
                        measurementId);

        assertEquals(
                PendingMeasurementCleanup.Status.DISCARDED,
                result.status);

        assertEquals(
                72.4f,
                result.weightKg,
                0.001f);

        assertEquals(
                2,
                result.closedQueued);

        assertTrue(
                PendingMeasurementStore.load(
                        pendingPrefs).isEmpty());

        assertTrue(
                PendingMeasurementStore.claimResponses(
                        pendingPrefs,
                        measurementId).isEmpty());

        List<PeerOutboxStore.Item> remaining =
                PeerOutboxStore.load(
                        outboxPrefs);

        assertEquals(
                3,
                remaining.size());

        assertTrue(
                remaining.stream().anyMatch(
                        item ->
                                "unrelated-message".equals(
                                        item.messageId)));

        assertEquals(
                2,
                remaining.stream()
                        .filter(
                                item ->
                                        PeerOutboxStore.KIND_CLOSED.equals(
                                                item.kind)
                                                && measurementId.equals(
                                                        item.dedupKey))
                        .count());

        assertFalse(
                remaining.stream().anyMatch(
                        item ->
                                measurementId.equals(
                                        item.dedupKey)
                                        && !PeerOutboxStore.KIND_CLOSED.equals(
                                                item.kind)));

        assertFalse(
                remaining.stream().anyMatch(
                        item ->
                                item.dedupKey.startsWith(
                                        measurementId + ":")));
    }
}
