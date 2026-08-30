package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
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
}
