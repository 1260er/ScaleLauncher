package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MeasurementWriteJournalStoreTest {
    private static final String MEASUREMENT_ID =
            "journal-measurement";

    private static final String AUTHORITY =
            "com.health.openscale";

    private static final long USER_ID =
            7L;

    private static final long TIMESTAMP =
            1_700_000_000_000L;

    @Test
    public void preparedMeasurementCanBecomeStoredButNotDowngraded() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        assertEquals(
                MeasurementWriteJournalStore.Status.MISSING,
                MeasurementWriteJournalStore.status(
                        prefs,
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID,
                        TIMESTAMP));

        assertTrue(
                MeasurementWriteJournalStore.prepare(
                        prefs,
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID,
                        TIMESTAMP));

        assertEquals(
                MeasurementWriteJournalStore.Status.PREPARED,
                MeasurementWriteJournalStore.status(
                        prefs,
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID,
                        TIMESTAMP));

        assertTrue(
                MeasurementWriteJournalStore.markStored(
                        prefs,
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID,
                        TIMESTAMP));

        assertEquals(
                MeasurementWriteJournalStore.Status.STORED,
                MeasurementWriteJournalStore.status(
                        prefs,
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID,
                        TIMESTAMP));

        assertTrue(
                MeasurementWriteJournalStore.prepare(
                        prefs,
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID,
                        TIMESTAMP));

        assertEquals(
                MeasurementWriteJournalStore.Status.STORED,
                MeasurementWriteJournalStore.status(
                        prefs,
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID,
                        TIMESTAMP));
    }

    @Test
    public void conflictingReuseOfMeasurementIdIsRejected() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        assertTrue(
                MeasurementWriteJournalStore.prepare(
                        prefs,
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID,
                        TIMESTAMP));

        assertEquals(
                MeasurementWriteJournalStore.Status.CONFLICT,
                MeasurementWriteJournalStore.status(
                        prefs,
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID + 1L,
                        TIMESTAMP));

        assertFalse(
                MeasurementWriteJournalStore.markStored(
                        prefs,
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID + 1L,
                        TIMESTAMP));

        assertEquals(
                MeasurementWriteJournalStore.Status.PREPARED,
                MeasurementWriteJournalStore.status(
                        prefs,
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID,
                        TIMESTAMP));
    }
}
