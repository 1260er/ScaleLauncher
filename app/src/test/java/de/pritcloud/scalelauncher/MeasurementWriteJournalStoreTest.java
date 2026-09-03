package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

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
        FakeDao dao =
                new FakeDao();

        assertEquals(
                MeasurementWriteJournalStore.Status.MISSING,
                status(
                        dao,
                        USER_ID));

        assertTrue(
                MeasurementWriteJournalStore.prepare(
                        dao,
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID,
                        TIMESTAMP));

        assertEquals(
                MeasurementWriteJournalStore.Status.PREPARED,
                status(
                        dao,
                        USER_ID));

        assertTrue(
                MeasurementWriteJournalStore.markStored(
                        dao,
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID,
                        TIMESTAMP));

        assertEquals(
                MeasurementWriteJournalStore.Status.STORED,
                status(
                        dao,
                        USER_ID));

        assertTrue(
                MeasurementWriteJournalStore.prepare(
                        dao,
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID,
                        TIMESTAMP));

        assertEquals(
                MeasurementWriteJournalStore.Status.STORED,
                status(
                        dao,
                        USER_ID));
    }

    @Test
    public void conflictingReuseOfMeasurementIdIsRejected() {
        FakeDao dao =
                new FakeDao();

        assertTrue(
                MeasurementWriteJournalStore.prepare(
                        dao,
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID,
                        TIMESTAMP));

        assertEquals(
                MeasurementWriteJournalStore.Status.CONFLICT,
                status(
                        dao,
                        USER_ID + 1L));

        assertFalse(
                MeasurementWriteJournalStore.markStored(
                        dao,
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID + 1L,
                        TIMESTAMP));

        assertEquals(
                MeasurementWriteJournalStore.Status.PREPARED,
                status(
                        dao,
                        USER_ID));
    }

    @Test
    public void legacyMigrationIsIdempotent() {
        InMemorySharedPreferences prefs =
                legacyPreferences(
                        legacyEntry(
                                MEASUREMENT_ID,
                                USER_ID,
                                "PREPARED",
                                100L));

        FakeDao dao =
                new FakeDao();

        assertTrue(
                MeasurementWriteJournalStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertTrue(
                MeasurementWriteJournalStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertTrue(
                MeasurementWriteJournalStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                1,
                dao.entries.size());

        assertEquals(
                MeasurementWriteJournalStore.Status.PREPARED,
                status(
                        dao,
                        USER_ID));
    }

    @Test
    public void duplicateLegacyEntriesKeepStoredState() {
        JSONArray array =
                new JSONArray();

        array.put(
                legacyEntry(
                        MEASUREMENT_ID,
                        USER_ID,
                        "PREPARED",
                        100L));

        array.put(
                legacyEntry(
                        MEASUREMENT_ID,
                        USER_ID,
                        "STORED",
                        200L));

        InMemorySharedPreferences prefs =
                preferences(
                        array);

        FakeDao dao =
                new FakeDao();

        assertTrue(
                MeasurementWriteJournalStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertEquals(
                1,
                dao.entries.size());

        assertEquals(
                MeasurementWriteJournalStore.Status.STORED,
                status(
                        dao,
                        USER_ID));

        assertEquals(
                200L,
                dao.entries.get(
                        MEASUREMENT_ID).updatedAtMs);
    }

    @Test
    public void conflictingLegacyDuplicateFailsClosed() {
        JSONArray array =
                new JSONArray();

        array.put(
                legacyEntry(
                        MEASUREMENT_ID,
                        USER_ID,
                        "PREPARED",
                        100L));

        array.put(
                legacyEntry(
                        MEASUREMENT_ID,
                        USER_ID + 1L,
                        "STORED",
                        200L));

        InMemorySharedPreferences prefs =
                preferences(
                        array);

        FakeDao dao =
                new FakeDao();

        assertFalse(
                MeasurementWriteJournalStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                MeasurementWriteJournalStore.isLegacyMigrationMarked(
                        prefs));

        assertTrue(
                dao.entries.isEmpty());
    }

    @Test
    public void malformedLegacyJournalFailsClosed() {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putString(
                        "entries",
                        "{broken")
                .commit();

        FakeDao dao =
                new FakeDao();

        assertFalse(
                MeasurementWriteJournalStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                MeasurementWriteJournalStore.isLegacyMigrationMarked(
                        prefs));

        assertTrue(
                dao.entries.isEmpty());
    }

    @Test
    public void partialMigrationCanBeRetried() {
        JSONArray array =
                new JSONArray();

        array.put(
                legacyEntry(
                        "measurement-1",
                        USER_ID,
                        "STORED",
                        100L));

        array.put(
                legacyEntry(
                        "measurement-2",
                        USER_ID,
                        "STORED",
                        200L));

        InMemorySharedPreferences prefs =
                preferences(
                        array);

        FakeDao dao =
                new FakeDao();

        dao.maxSuccessfulInserts = 1;

        assertFalse(
                MeasurementWriteJournalStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertFalse(
                MeasurementWriteJournalStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                1,
                dao.entries.size());

        dao.maxSuccessfulInserts =
                Integer.MAX_VALUE;

        assertTrue(
                MeasurementWriteJournalStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertTrue(
                MeasurementWriteJournalStore.isLegacyMigrationMarked(
                        prefs));

        assertEquals(
                2,
                dao.entries.size());
    }

    @Test
    public void legacyPreparedCannotDowngradeStoredRoomEntry() {
        InMemorySharedPreferences prefs =
                legacyPreferences(
                        legacyEntry(
                                MEASUREMENT_ID,
                                USER_ID,
                                "PREPARED",
                                100L));

        FakeDao dao =
                new FakeDao();

        dao.insert(
                new MeasurementWriteJournalEntity(
                        MEASUREMENT_ID,
                        AUTHORITY,
                        USER_ID,
                        TIMESTAMP,
                        "STORED",
                        200L));

        assertTrue(
                MeasurementWriteJournalStore.migrateLegacyForTest(
                        prefs,
                        dao));

        assertEquals(
                MeasurementWriteJournalStore.Status.STORED,
                status(
                        dao,
                        USER_ID));

        assertEquals(
                200L,
                dao.entries.get(
                        MEASUREMENT_ID).updatedAtMs);
    }

    @Test
    public void keepsEntriesBeyondFormerThousandItemLimit() {
        FakeDao dao =
                new FakeDao();

        for (int index = 0;
             index <= 1000;
             index++) {
            assertTrue(
                    MeasurementWriteJournalStore.markStored(
                            dao,
                            "stored-measurement-" + index,
                            AUTHORITY,
                            USER_ID,
                            TIMESTAMP + index));
        }

        assertEquals(
                1001,
                dao.entries.size());
    }

    private static MeasurementWriteJournalStore.Status status(
            FakeDao dao,
            long userId) {
        return MeasurementWriteJournalStore.status(
                dao,
                MEASUREMENT_ID,
                AUTHORITY,
                userId,
                TIMESTAMP);
    }

    private static JSONObject legacyEntry(
            String measurementId,
            long userId,
            String status,
            long updatedAtMs) {
        try {
            JSONObject object =
                    new JSONObject();

            object.put(
                    "measurementId",
                    measurementId);

            object.put(
                    "authority",
                    AUTHORITY);

            object.put(
                    "userId",
                    userId);

            object.put(
                    "timestampMs",
                    TIMESTAMP);

            object.put(
                    "status",
                    status);

            object.put(
                    "updatedAtMs",
                    updatedAtMs);

            return object;
        } catch (Exception e) {
            throw new AssertionError(
                    "Failed to create legacy journal entry",
                    e);
        }
    }

    private static InMemorySharedPreferences legacyPreferences(
            JSONObject entry) {
        JSONArray array =
                new JSONArray();

        array.put(
                entry);

        return preferences(
                array);
    }

    private static InMemorySharedPreferences preferences(
            JSONArray array) {
        InMemorySharedPreferences prefs =
                new InMemorySharedPreferences();

        prefs.edit()
                .putString(
                        "entries",
                        array.toString())
                .commit();

        return prefs;
    }

    private static final class FakeDao
            implements MeasurementWriteJournalDao {
        final Map<String, MeasurementWriteJournalEntity> entries =
                new LinkedHashMap<>();

        int successfulInserts;
        int maxSuccessfulInserts =
                Integer.MAX_VALUE;

        @Override
        public MeasurementWriteJournalEntity findByMeasurementId(
                String measurementId) {
            return entries.get(
                    measurementId);
        }

        @Override
        public long insert(
                MeasurementWriteJournalEntity entry) {
            if (entries.containsKey(
                    entry.measurementId)
                    || successfulInserts
                    >= maxSuccessfulInserts) {
                return -1L;
            }

            entries.put(
                    entry.measurementId,
                    entry);

            successfulInserts++;
            return successfulInserts;
        }

        @Override
        public int update(
                MeasurementWriteJournalEntity entry) {
            if (!entries.containsKey(
                    entry.measurementId)) {
                return 0;
            }

            entries.put(
                    entry.measurementId,
                    entry);

            return 1;
        }
    }
}
