package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class PendingMeasurementStoreTest {
    @Test
    public void keepsMeasurementsBeyondFormerTenItemLimit() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        for (int index = 0; index < 12; index++) {
            PendingMeasurementStore.add(
                    prefs,
                    new S400FinalMeasurement(
                            "measurement-" + index,
                            70.0f + index,
                            510.0f,
                            490.0f,
                            1_700_000_000_000L + index,
                            null),
                    "test");
        }

        List<PendingMeasurementStore.Item> items =
                PendingMeasurementStore.load(
                        prefs);

        assertEquals(
                12,
                items.size());

        assertEquals(
                "measurement-0",
                items.get(0).id);

        assertEquals(
                "measurement-11",
                items.get(11).id);
    }
    @Test
    public void keepsClaimResponsesBeyondFormerHundredItemLimit() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        String measurementId =
                "claim-measurement";

        for (int index = 0; index < 101; index++) {
            String peerDeviceId =
                    String.format(
                            "00000000-0000-4000-8000-%012d",
                            index + 1);

            PendingMeasurementStore.recordClaimResponse(
                    prefs,
                    measurementId,
                    peerDeviceId,
                    List.of());
        }

        List<PendingMeasurementStore.ClaimResponse> responses =
                PendingMeasurementStore.claimResponses(
                        prefs,
                        measurementId);

        assertEquals(
                101,
                responses.size());

        assertEquals(
                "00000000-0000-4000-8000-000000000001",
                responses.get(0).peerDeviceId);

        assertEquals(
                "00000000-0000-4000-8000-000000000101",
                responses.get(100).peerDeviceId);
    }


    @Test
    public void rejectsSelectedCandidateWhenOwnerDisappears() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        String selectedProfileId =
                "11111111-1111-4111-8111-111111111111";

        String remainingProfileId =
                "22222222-2222-4222-8222-222222222222";

        String remoteOwnerId =
                "33333333-3333-4333-8333-333333333333";

        PendingMeasurementStore.Item pending =
                PendingMeasurementStore.add(
                        prefs,
                        new S400FinalMeasurement(
                                "selected-remote",
                                70.0f,
                                510.0f,
                                490.0f,
                                1_700_000_000_000L,
                                null),
                        "test",
                        List.of(
                                selectedProfileId,
                                remainingProfileId));

        assertTrue(
                PendingMeasurementStore.selectCandidate(
                        prefs,
                        pending.id,
                        selectedProfileId,
                        remoteOwnerId));

        assertTrue(
                PendingMeasurementStore.rejectSelectedCandidate(
                        prefs,
                        pending.id,
                        selectedProfileId,
                        remoteOwnerId));

        PendingMeasurementStore.Item repaired =
                PendingMeasurementStore.find(
                        prefs,
                        pending.id);

        assertFalse(repaired.isResolved());

        assertEquals(
                List.of(remainingProfileId),
                repaired.remainingCandidateProfileIds());
    }


    @Test
    public void removesOnlyClaimResponsesFromRemovedPeer() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        String removedPeer =
                "11111111-1111-4111-8111-111111111111";

        String retainedPeer =
                "22222222-2222-4222-8222-222222222222";

        PendingMeasurementStore.recordClaimResponse(
                prefs,
                "measurement-one",
                removedPeer,
                List.of());

        PendingMeasurementStore.recordClaimResponse(
                prefs,
                "measurement-one",
                retainedPeer,
                List.of());

        PendingMeasurementStore.recordClaimResponse(
                prefs,
                "measurement-two",
                removedPeer,
                List.of());

        assertEquals(
                2,
                PendingMeasurementStore.removeClaimResponsesForPeer(
                        prefs,
                        removedPeer));

        List<PendingMeasurementStore.ClaimResponse> remaining =
                PendingMeasurementStore.claimResponses(
                        prefs,
                        "measurement-one");

        assertEquals(
                1,
                remaining.size());

        assertEquals(
                retainedPeer,
                remaining.get(0).peerDeviceId);

        assertEquals(
                0,
                PendingMeasurementStore.claimResponses(
                        prefs,
                        "measurement-two").size());
    }


    @Test
    public void reusesExistingPendingForDuplicateMeasurementId() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        String firstProfileId =
                "11111111-1111-4111-8111-111111111111";

        String secondProfileId =
                "22222222-2222-4222-8222-222222222222";

        String ownerDeviceId =
                "33333333-3333-4333-8333-333333333333";

        S400FinalMeasurement measurement =
                new S400FinalMeasurement(
                        "duplicate-measurement",
                        70.0f,
                        510.0f,
                        490.0f,
                        1_700_000_000_000L,
                        null);

        PendingMeasurementStore.Item first =
                PendingMeasurementStore.add(
                        prefs,
                        measurement,
                        "first",
                        List.of(
                                firstProfileId,
                                secondProfileId));

        assertTrue(
                PendingMeasurementStore.selectCandidate(
                        prefs,
                        first.id,
                        firstProfileId,
                        ownerDeviceId));

        PendingMeasurementStore.Item duplicate =
                PendingMeasurementStore.add(
                        prefs,
                        measurement,
                        "replacement",
                        List.of(secondProfileId),
                        true);

        assertEquals(
                1,
                PendingMeasurementStore.load(prefs).size());

        assertTrue(duplicate.isResolved());
        assertEquals(
                firstProfileId,
                duplicate.selectedProfileId);
        assertEquals(
                ownerDeviceId,
                duplicate.selectedOwnerDeviceId);
        assertFalse(duplicate.manualRescue);
        assertEquals(
                List.of(
                        firstProfileId,
                        secondProfileId),
                duplicate.candidateProfileIds);
    }


    @Test
    public void removingPendingAlsoRemovesItsClaimResponses() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        String measurementId =
                "discard-measurement";

        String profileId =
                "11111111-1111-4111-8111-111111111111";

        String peerDeviceId =
                "22222222-2222-4222-8222-222222222222";

        PendingMeasurementStore.add(
                prefs,
                new S400FinalMeasurement(
                        measurementId,
                        72.0f,
                        510.0f,
                        490.0f,
                        1_700_000_000_000L,
                        null),
                "test",
                List.of(profileId));

        PendingMeasurementStore.recordClaimResponse(
                prefs,
                measurementId,
                peerDeviceId,
                List.of(profileId));

        PendingMeasurementStore.remove(
                prefs,
                measurementId);

        assertEquals(
                0,
                PendingMeasurementStore.load(
                        prefs).size());

        assertEquals(
                0,
                PendingMeasurementStore.claimResponses(
                        prefs,
                        measurementId).size());
    }


}
