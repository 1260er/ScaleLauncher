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
}
