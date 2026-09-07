package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RoutedMeasurementAcceptanceRoomStoreTest {
    private static final String PEER =
            "11111111-1111-1111-1111-111111111111";

    private static final String SCALE_MAC =
            "04:AE:47:67:4E:07";

    private static final String PROFILE =
            "22222222-2222-2222-2222-222222222222";

    @Test
    public void newMeasurementPersistsQueueAndFingerprint() {
        FakeDedupDao dedup =
                new FakeDedupDao();

        FakePendingDao pending =
                new FakePendingDao();

        PeerMeasurementPayload payload =
                payload(
                        70.7f);

        assertEquals(
                PeerInboxDedupRoomStore.FingerprintStatus.NEW,
                RoutedMeasurementAcceptanceRoomStore.accept(
                        dedup,
                        pending,
                        PEER,
                        42L,
                        payload,
                        10L));

        assertEquals(
                1,
                pending.countForProfile(
                        PROFILE));

        PeerInboxDedupEntity stored =
                dedup.find(
                        PEER,
                        "routed-measurement:"
                                + payload.measurementId);

        assertEquals(
                payload.routedPayloadFingerprint(),
                stored.payloadFingerprint);
    }

    @Test
    public void exactRepeatAfterQueueRemovalDoesNotRecreateQueue() {
        FakeDedupDao dedup =
                new FakeDedupDao();

        FakePendingDao pending =
                new FakePendingDao();

        PeerMeasurementPayload payload =
                payload(
                        70.7f);

        assertEquals(
                PeerInboxDedupRoomStore.FingerprintStatus.NEW,
                RoutedMeasurementAcceptanceRoomStore.accept(
                        dedup,
                        pending,
                        PEER,
                        42L,
                        payload,
                        10L));

        assertEquals(
                1,
                pending.delete(
                        payload.measurementId));

        assertEquals(
                PeerInboxDedupRoomStore.FingerprintStatus.MATCH,
                RoutedMeasurementAcceptanceRoomStore.accept(
                        dedup,
                        pending,
                        PEER,
                        42L,
                        payload,
                        20L));

        assertEquals(
                0,
                pending.countForProfile(
                        PROFILE));
    }

    @Test
    public void conflictingRepeatFailsClosedWithoutQueueRecreation() {
        FakeDedupDao dedup =
                new FakeDedupDao();

        FakePendingDao pending =
                new FakePendingDao();

        PeerMeasurementPayload original =
                payload(
                        70.7f);

        RoutedMeasurementAcceptanceRoomStore.accept(
                dedup,
                pending,
                PEER,
                42L,
                original,
                10L);

        pending.delete(
                original.measurementId);

        PeerMeasurementPayload changed =
                payload(
                        70.8f);

        assertEquals(
                PeerInboxDedupRoomStore.FingerprintStatus.CONFLICT,
                RoutedMeasurementAcceptanceRoomStore.accept(
                        dedup,
                        pending,
                        PEER,
                        42L,
                        changed,
                        20L));

        assertEquals(
                0,
                pending.countForProfile(
                        PROFILE));
    }

    @Test
    public void legacyUnknownFailsClosedWithoutQueueCreation() {
        FakeDedupDao dedup =
                new FakeDedupDao();

        FakePendingDao pending =
                new FakePendingDao();

        PeerMeasurementPayload payload =
                payload(
                        70.7f);

        dedup.insert(
                new PeerInboxDedupEntity(
                        PEER,
                        "routed-measurement:"
                                + payload.measurementId,
                        5L));

        assertEquals(
                PeerInboxDedupRoomStore.FingerprintStatus.LEGACY_UNKNOWN,
                RoutedMeasurementAcceptanceRoomStore.accept(
                        dedup,
                        pending,
                        PEER,
                        42L,
                        payload,
                        10L));

        assertEquals(
                0,
                pending.countForProfile(
                        PROFILE));
    }

    @Test
    public void conflictingPendingMeasurementPreventsDedupInsert() {
        FakeDedupDao dedup =
                new FakeDedupDao();

        FakePendingDao pending =
                new FakePendingDao();

        PeerMeasurementPayload original =
                payload(
                        70.7f);

        OpenScalePendingRoomStore.add(
                pending,
                42L,
                PROFILE,
                original.toMeasurement(),
                5L);

        PeerMeasurementPayload changed =
                payload(
                        70.8f);

        assertThrows(
                IllegalStateException.class,
                () ->
                        RoutedMeasurementAcceptanceRoomStore.accept(
                                dedup,
                                pending,
                                PEER,
                                42L,
                                changed,
                                10L));

        assertNull(
                dedup.find(
                        PEER,
                        "routed-measurement:"
                                + original.measurementId));
    }

    private static PeerMeasurementPayload payload(
            float weightKg) {
        return PeerMeasurementPayload.forUniqueTarget(
                SCALE_MAC,
                new S400FinalMeasurement(
                        "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb",
                        weightKg,
                        510.0f,
                        490.0f,
                        1_700_000_000_000L,
                        7),
                PROFILE);
    }

    private static final class FakeDedupDao
            implements PeerInboxDedupDao {
        private final Map<String, PeerInboxDedupEntity> items =
                new LinkedHashMap<>();

        @Override
        public PeerInboxDedupEntity find(
                String senderDeviceId,
                String messageId) {
            return items.get(
                    key(
                            senderDeviceId,
                            messageId));
        }

        @Override
        public boolean contains(
                String senderDeviceId,
                String messageId) {
            return find(
                    senderDeviceId,
                    messageId) != null;
        }

        @Override
        public long upsert(
                PeerInboxDedupEntity entity) {
            items.put(
                    key(
                            entity.senderDeviceId,
                            entity.messageId),
                    entity);

            return 1L;
        }

        @Override
        public long insert(
                PeerInboxDedupEntity entity) {
            String key =
                    key(
                            entity.senderDeviceId,
                            entity.messageId);

            if (items.containsKey(
                    key)) {
                return -1L;
            }

            items.put(
                    key,
                    entity);

            return 1L;
        }

        @Override
        public int deletePeer(
                String peerDeviceId) {
            int before =
                    items.size();

            items.entrySet()
                    .removeIf(
                            entry ->
                                    entry.getValue()
                                            .senderDeviceId
                                            .equals(
                                                    peerDeviceId));

            return before
                    - items.size();
        }

        @Override
        public int count() {
            return items.size();
        }

        private static String key(
                String senderDeviceId,
                String messageId) {
            return senderDeviceId
                    + "|"
                    + messageId;
        }
    }

    private static final class FakePendingDao
            implements OpenScalePendingDao {
        private final Map<String, OpenScalePendingEntity> items =
                new LinkedHashMap<>();

        @Override
        public List<OpenScalePendingEntity> loadAll() {
            return new ArrayList<>(
                    items.values());
        }

        @Override
        public List<OpenScalePendingEntity> loadForProfile(
                String householdProfileId) {
            List<OpenScalePendingEntity> result =
                    new ArrayList<>();

            for (OpenScalePendingEntity entity :
                    items.values()) {
                if (entity.householdProfileId.equals(
                        householdProfileId)) {
                    result.add(
                            entity);
                }
            }

            return result;
        }

        @Override
        public OpenScalePendingEntity find(
                String measurementId) {
            return items.get(
                    measurementId);
        }

        @Override
        public long insert(
                OpenScalePendingEntity entity) {
            if (items.containsKey(
                    entity.measurementId)) {
                return -1L;
            }

            items.put(
                    entity.measurementId,
                    entity);

            return 1L;
        }

        @Override
        public int delete(
                String measurementId) {
            return items.remove(
                    measurementId) == null
                    ? 0
                    : 1;
        }

        @Override
        public int countForProfile(
                String householdProfileId) {
            int count =
                    0;

            for (OpenScalePendingEntity entity :
                    items.values()) {
                if (entity.householdProfileId.equals(
                        householdProfileId)) {
                    count++;
                }
            }

            return count;
        }
    }
}
