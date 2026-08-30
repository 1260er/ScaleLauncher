package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;

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

}
