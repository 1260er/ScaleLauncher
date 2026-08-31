package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class RemotePendingMeasurementStoreTest {
    @Test
    public void keepsMeasurementsBeyondFormerTenItemLimit() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PeerTrustStore.Peer collector =
                new PeerTrustStore.Peer(
                        "11111111-1111-4111-8111-111111111111",
                        "Collector",
                        new byte[32]);

        String candidateProfileId =
                "22222222-2222-4222-8222-222222222222";

        for (int index = 0; index < 12; index++) {
            S400FinalMeasurement measurement =
                    new S400FinalMeasurement(
                            "measurement-" + index,
                            70.0f + index,
                            510.0f,
                            490.0f,
                            1_700_000_000_000L + index,
                            null);

            PeerMeasurementPayload payload =
                    PeerMeasurementPayload.forClaim(
                            "04:AE:47:67:4E:07",
                            measurement,
                            List.of(candidateProfileId));

            assertTrue(
                    RemotePendingMeasurementStore.upsert(
                            prefs,
                            collector,
                            payload,
                            List.of(candidateProfileId)));
        }

        List<RemotePendingMeasurementStore.Item> items =
                RemotePendingMeasurementStore.load(prefs);

        assertEquals(12, items.size());
        assertEquals("measurement-0", items.get(0).measurementId);
        assertEquals("measurement-11", items.get(11).measurementId);
    }

    @Test
    public void removeCollectorRemovesOnlyItsRemotePendingMeasurements() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PeerTrustStore.Peer firstCollector =
                new PeerTrustStore.Peer(
                        "11111111-1111-4111-8111-111111111111",
                        "Collector 1",
                        new byte[32]);

        PeerTrustStore.Peer secondCollector =
                new PeerTrustStore.Peer(
                        "33333333-3333-4333-8333-333333333333",
                        "Collector 2",
                        new byte[32]);

        String candidateProfileId =
                "22222222-2222-4222-8222-222222222222";

        S400FinalMeasurement firstMeasurement =
                new S400FinalMeasurement(
                        "collector-one-measurement",
                        70.0f,
                        510.0f,
                        490.0f,
                        1_700_000_000_000L,
                        null);

        S400FinalMeasurement secondMeasurement =
                new S400FinalMeasurement(
                        "collector-two-measurement",
                        71.0f,
                        510.0f,
                        490.0f,
                        1_700_000_000_001L,
                        null);

        assertTrue(
                RemotePendingMeasurementStore.upsert(
                        prefs,
                        firstCollector,
                        PeerMeasurementPayload.forClaim(
                                "04:AE:47:67:4E:07",
                                firstMeasurement,
                                List.of(candidateProfileId)),
                        List.of(candidateProfileId)));

        assertTrue(
                RemotePendingMeasurementStore.upsert(
                        prefs,
                        secondCollector,
                        PeerMeasurementPayload.forClaim(
                                "04:AE:47:67:4E:07",
                                secondMeasurement,
                                List.of(candidateProfileId)),
                        List.of(candidateProfileId)));

        assertEquals(
                1,
                RemotePendingMeasurementStore.removeCollector(
                        prefs,
                        firstCollector.deviceId));

        List<RemotePendingMeasurementStore.Item> remaining =
                RemotePendingMeasurementStore.load(
                        prefs);

        assertEquals(
                1,
                remaining.size());

        assertEquals(
                secondCollector.deviceId,
                remaining.get(0).collectorDeviceId);

        assertEquals(
                "collector-two-measurement",
                remaining.get(0).measurementId);
    }



    @Test
    public void rejectsSameMeasurementIdFromDifferentCollector() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PeerTrustStore.Peer firstCollector =
                new PeerTrustStore.Peer(
                        "11111111-1111-4111-8111-111111111111",
                        "Collector 1",
                        new byte[32]);

        PeerTrustStore.Peer secondCollector =
                new PeerTrustStore.Peer(
                        "33333333-3333-4333-8333-333333333333",
                        "Collector 2",
                        new byte[32]);

        String candidateProfileId =
                "22222222-2222-4222-8222-222222222222";

        S400FinalMeasurement firstMeasurement =
                new S400FinalMeasurement(
                        "shared-measurement-id",
                        70.0f,
                        510.0f,
                        490.0f,
                        1_700_000_000_000L,
                        null);

        S400FinalMeasurement conflictingMeasurement =
                new S400FinalMeasurement(
                        "shared-measurement-id",
                        80.0f,
                        520.0f,
                        480.0f,
                        1_700_000_000_001L,
                        null);

        assertTrue(
                RemotePendingMeasurementStore.upsert(
                        prefs,
                        firstCollector,
                        PeerMeasurementPayload.forClaim(
                                "04:AE:47:67:4E:07",
                                firstMeasurement,
                                List.of(candidateProfileId)),
                        List.of(candidateProfileId)));

        assertFalse(
                RemotePendingMeasurementStore.upsert(
                        prefs,
                        secondCollector,
                        PeerMeasurementPayload.forClaim(
                                "04:AE:47:67:4E:07",
                                conflictingMeasurement,
                                List.of(candidateProfileId)),
                        List.of(candidateProfileId)));

        List<RemotePendingMeasurementStore.Item> stored =
                RemotePendingMeasurementStore.load(
                        prefs);

        assertEquals(
                1,
                stored.size());

        assertEquals(
                firstCollector.deviceId,
                stored.get(0).collectorDeviceId);

        assertEquals(
                70.0f,
                stored.get(0).weightKg,
                0.001f);
    }



    @Test
    public void sameCollectorUpdatesExistingRemotePendingMeasurement() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PeerTrustStore.Peer collector =
                new PeerTrustStore.Peer(
                        "11111111-1111-4111-8111-111111111111",
                        "Collector",
                        new byte[32]);

        String firstProfileId =
                "22222222-2222-4222-8222-222222222222";

        String updatedProfileId =
                "33333333-3333-4333-8333-333333333333";

        S400FinalMeasurement firstMeasurement =
                new S400FinalMeasurement(
                        "same-measurement-id",
                        70.0f,
                        510.0f,
                        490.0f,
                        1_700_000_000_000L,
                        null);

        S400FinalMeasurement updatedMeasurement =
                new S400FinalMeasurement(
                        "same-measurement-id",
                        71.5f,
                        520.0f,
                        480.0f,
                        1_700_000_000_001L,
                        null);

        assertTrue(
                RemotePendingMeasurementStore.upsert(
                        prefs,
                        collector,
                        PeerMeasurementPayload.forClaim(
                                "04:AE:47:67:4E:07",
                                firstMeasurement,
                                List.of(firstProfileId)),
                        List.of(firstProfileId)));

        assertTrue(
                RemotePendingMeasurementStore.upsert(
                        prefs,
                        collector,
                        PeerMeasurementPayload.forClaim(
                                "04:AE:47:67:4E:07",
                                updatedMeasurement,
                                List.of(updatedProfileId)),
                        List.of(updatedProfileId)));

        List<RemotePendingMeasurementStore.Item> stored =
                RemotePendingMeasurementStore.load(
                        prefs);

        assertEquals(
                1,
                stored.size());

        assertEquals(
                71.5f,
                stored.get(0).weightKg,
                0.001f);

        assertEquals(
                List.of(updatedProfileId),
                stored.get(0).candidateProfileIds);
    }



    @Test
    public void removeDeletesOnlySelectedRemotePendingMeasurement() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PeerTrustStore.Peer collector =
                new PeerTrustStore.Peer(
                        "11111111-1111-4111-8111-111111111111",
                        "Collector",
                        new byte[32]);

        String candidateProfileId =
                "22222222-2222-4222-8222-222222222222";

        for (int index = 1; index <= 2; index++) {
            S400FinalMeasurement measurement =
                    new S400FinalMeasurement(
                            "measurement-" + index,
                            70.0f + index,
                            510.0f,
                            490.0f,
                            1_700_000_000_000L + index,
                            null);

            assertTrue(
                    RemotePendingMeasurementStore.upsert(
                            prefs,
                            collector,
                            PeerMeasurementPayload.forClaim(
                                    "04:AE:47:67:4E:07",
                                    measurement,
                                    List.of(candidateProfileId)),
                            List.of(candidateProfileId)));
        }

        assertTrue(
                RemotePendingMeasurementStore.remove(
                        prefs,
                        "measurement-1"));

        List<RemotePendingMeasurementStore.Item> remaining =
                RemotePendingMeasurementStore.load(
                        prefs);

        assertEquals(
                1,
                remaining.size());

        assertEquals(
                "measurement-2",
                remaining.get(0).measurementId);

        assertFalse(
                RemotePendingMeasurementStore.remove(
                        prefs,
                        "measurement-does-not-exist"));
    }



    @Test
    public void rejectsRemotePendingWithoutValidCandidateProfile() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PeerTrustStore.Peer collector =
                new PeerTrustStore.Peer(
                        "11111111-1111-4111-8111-111111111111",
                        "Collector",
                        new byte[32]);

        S400FinalMeasurement measurement =
                new S400FinalMeasurement(
                        "measurement-without-valid-candidate",
                        70.0f,
                        510.0f,
                        490.0f,
                        1_700_000_000_000L,
                        null);

        assertFalse(
                RemotePendingMeasurementStore.upsert(
                        prefs,
                        collector,
                        PeerMeasurementPayload.forClaim(
                                "04:AE:47:67:4E:07",
                                measurement,
                                List.of(
                                        "22222222-2222-4222-8222-222222222222")),
                        List.of(
                                "ungueltige-profil-id")));

        assertEquals(
                0,
                RemotePendingMeasurementStore.load(
                        prefs).size());
    }



    @Test
    public void candidateProfilesAreSanitizedAndDeduplicated() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        PeerTrustStore.Peer collector =
                new PeerTrustStore.Peer(
                        "11111111-1111-4111-8111-111111111111",
                        "Collector",
                        new byte[32]);

        String firstProfileId =
                "22222222-2222-4222-8222-222222222222";

        String secondProfileId =
                "33333333-3333-4333-8333-333333333333";

        S400FinalMeasurement measurement =
                new S400FinalMeasurement(
                        "sanitized-remote-candidates",
                        70.0f,
                        510.0f,
                        490.0f,
                        1_700_000_000_000L,
                        null);

        assertTrue(
                RemotePendingMeasurementStore.upsert(
                        prefs,
                        collector,
                        PeerMeasurementPayload.forClaim(
                                "04:AE:47:67:4E:07",
                                measurement,
                                List.of(firstProfileId)),
                        List.of(
                                firstProfileId,
                                "ungueltige-profil-id",
                                firstProfileId,
                                secondProfileId,
                                secondProfileId)));

        List<RemotePendingMeasurementStore.Item> stored =
                RemotePendingMeasurementStore.load(
                        prefs);

        assertEquals(
                1,
                stored.size());

        assertEquals(
                List.of(
                        firstProfileId,
                        secondProfileId),
                stored.get(0).candidateProfileIds);
    }


}
