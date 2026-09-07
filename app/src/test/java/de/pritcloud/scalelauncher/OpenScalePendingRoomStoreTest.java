package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpenScalePendingRoomStoreTest {
    private static final String PROFILE_ONE =
            "11111111-1111-4111-8111-111111111111";

    private static final String PROFILE_TWO =
            "22222222-2222-4222-8222-222222222222";

    @Test
    public void storesAndReconstructsCompleteMeasurement() {
        FakeDao dao =
                new FakeDao();

        OpenScalePendingRoomStore.Item stored =
                OpenScalePendingRoomStore.add(
                        dao,
                        7L,
                        PROFILE_ONE,
                        measurement(
                                "measurement-1",
                                70.5f,
                                1_700_000_000_000L),
                        123L);

        assertEquals(
                7L,
                stored.userId);
        assertEquals(
                PROFILE_ONE,
                stored.householdProfileId);
        assertEquals(
                123L,
                stored.queuedAtMs);

        assertEquals(
                "measurement-1",
                stored.measurement.measurementId);
        assertEquals(
                70.5f,
                stored.measurement.weightKg,
                0.0f);
        assertEquals(
                510.0f,
                stored.measurement.impedanceHigh,
                0.0f);
        assertEquals(
                Float.valueOf(490.0f),
                stored.measurement.impedanceLow);
        assertEquals(
                Integer.valueOf(3),
                stored.measurement.scaleProfileId);
        assertEquals(
                1_700_000_000_000L,
                stored.measurement.timestampMs);

        OpenScalePendingRoomStore.Item found =
                OpenScalePendingRoomStore.find(
                        dao,
                        "measurement-1");

        assertNotNull(
                found);
        assertEquals(
                123L,
                found.queuedAtMs);
    }

    @Test
    public void identicalMeasurementIdIsIdempotent() {
        FakeDao dao =
                new FakeDao();

        S400FinalMeasurement measurement =
                measurement(
                        "same",
                        70.5f,
                        1_700_000_000_000L);

        OpenScalePendingRoomStore.Item first =
                OpenScalePendingRoomStore.add(
                        dao,
                        7L,
                        PROFILE_ONE,
                        measurement,
                        100L);

        OpenScalePendingRoomStore.Item second =
                OpenScalePendingRoomStore.add(
                        dao,
                        7L,
                        PROFILE_ONE,
                        measurement,
                        200L);

        assertEquals(
                1,
                dao.loadAll().size());

        assertEquals(
                100L,
                first.queuedAtMs);
        assertEquals(
                100L,
                second.queuedAtMs);
    }

    @Test
    public void conflictingMeasurementDataFailsClosed() {
        FakeDao dao =
                new FakeDao();

        OpenScalePendingRoomStore.add(
                dao,
                7L,
                PROFILE_ONE,
                measurement(
                        "conflict",
                        70.5f,
                        1_700_000_000_000L),
                100L);

        try {
            OpenScalePendingRoomStore.add(
                    dao,
                    7L,
                    PROFILE_ONE,
                    measurement(
                            "conflict",
                            71.0f,
                            1_700_000_000_000L),
                    200L);

            fail(
                    "Expected conflicting measurement to fail closed");
        } catch (IllegalStateException expected) {
            assertTrue(
                    expected.getMessage().contains(
                            "conflict"));
        }

        assertEquals(
                1,
                dao.loadAll().size());

        assertEquals(
                70.5f,
                dao.find("conflict").weightKg,
                0.0f);
    }

    @Test
    public void conflictingUserIdFailsClosed() {
        FakeDao dao =
                new FakeDao();

        S400FinalMeasurement measurement =
                measurement(
                        "user-conflict",
                        70.5f,
                        1_700_000_000_000L);

        OpenScalePendingRoomStore.add(
                dao,
                7L,
                PROFILE_ONE,
                measurement,
                100L);

        try {
            OpenScalePendingRoomStore.add(
                    dao,
                    8L,
                    PROFILE_ONE,
                    measurement,
                    200L);

            fail(
                    "Expected conflicting user ID to fail closed");
        } catch (IllegalStateException expected) {
            assertTrue(
                    expected.getMessage().contains(
                            "user-conflict"));
        }

        assertEquals(
                1,
                dao.loadAll().size());
    }

    @Test
    public void conflictingProfileFailsClosed() {
        FakeDao dao =
                new FakeDao();

        S400FinalMeasurement measurement =
                measurement(
                        "profile-conflict",
                        70.5f,
                        1_700_000_000_000L);

        OpenScalePendingRoomStore.add(
                dao,
                7L,
                PROFILE_ONE,
                measurement,
                100L);

        try {
            OpenScalePendingRoomStore.add(
                    dao,
                    7L,
                    PROFILE_TWO,
                    measurement,
                    200L);

            fail(
                    "Expected conflicting profile to fail closed");
        } catch (IllegalStateException expected) {
            assertTrue(
                    expected.getMessage().contains(
                            "profile-conflict"));
        }

        assertEquals(
                1,
                dao.loadAll().size());
    }

    @Test
    public void loadForProfileIsChronologicalAndSeparated() {
        FakeDao dao =
                new FakeDao();

        OpenScalePendingRoomStore.add(
                dao,
                7L,
                PROFILE_ONE,
                measurement(
                        "third",
                        73.0f,
                        300L),
                30L);

        OpenScalePendingRoomStore.add(
                dao,
                7L,
                PROFILE_ONE,
                measurement(
                        "first",
                        71.0f,
                        100L),
                10L);

        OpenScalePendingRoomStore.add(
                dao,
                7L,
                PROFILE_ONE,
                measurement(
                        "second",
                        72.0f,
                        200L),
                20L);

        OpenScalePendingRoomStore.add(
                dao,
                8L,
                PROFILE_TWO,
                measurement(
                        "other-profile",
                        80.0f,
                        50L),
                5L);

        List<OpenScalePendingRoomStore.Item> items =
                OpenScalePendingRoomStore.loadForProfile(
                        dao,
                        PROFILE_ONE);

        assertEquals(
                3,
                items.size());

        assertEquals(
                "first",
                items.get(0).measurement.measurementId);
        assertEquals(
                "second",
                items.get(1).measurement.measurementId);
        assertEquals(
                "third",
                items.get(2).measurement.measurementId);
    }

    @Test
    public void removeAndCountAreScopedSafely() {
        FakeDao dao =
                new FakeDao();

        OpenScalePendingRoomStore.add(
                dao,
                7L,
                PROFILE_ONE,
                measurement(
                        "one",
                        71.0f,
                        100L),
                10L);

        OpenScalePendingRoomStore.add(
                dao,
                7L,
                PROFILE_ONE,
                measurement(
                        "two",
                        72.0f,
                        200L),
                20L);

        OpenScalePendingRoomStore.add(
                dao,
                8L,
                PROFILE_TWO,
                measurement(
                        "three",
                        80.0f,
                        300L),
                30L);

        assertEquals(
                2,
                OpenScalePendingRoomStore.count(
                        dao,
                        PROFILE_ONE));

        assertTrue(
                OpenScalePendingRoomStore.remove(
                        dao,
                        "one"));

        assertFalse(
                OpenScalePendingRoomStore.remove(
                        dao,
                        "one"));

        assertEquals(
                1,
                OpenScalePendingRoomStore.count(
                        dao,
                        PROFILE_ONE));

        assertEquals(
                1,
                OpenScalePendingRoomStore.count(
                        dao,
                        PROFILE_TWO));
    }

    private static S400FinalMeasurement measurement(
            String id,
            float weightKg,
            long timestampMs) {
        return new S400FinalMeasurement(
                id,
                weightKg,
                510.0f,
                490.0f,
                timestampMs,
                3);
    }

    private static final class FakeDao
            implements OpenScalePendingDao {
        private final Map<String, OpenScalePendingEntity> items =
                new LinkedHashMap<>();

        @Override
        public List<OpenScalePendingEntity> loadAll() {
            List<OpenScalePendingEntity> result =
                    new ArrayList<>(
                            items.values());

            sort(
                    result);

            return result;
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

            sort(
                    result);

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

            return items.size();
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

        private static void sort(
                List<OpenScalePendingEntity> entities) {
            entities.sort(
                    (first, second) -> {
                        int timestamp =
                                Long.compare(
                                        first.timestampMs,
                                        second.timestampMs);

                        if (timestamp != 0) {
                            return timestamp;
                        }

                        return Long.compare(
                                first.queuedAtMs,
                                second.queuedAtMs);
                    });
        }
    }
}
