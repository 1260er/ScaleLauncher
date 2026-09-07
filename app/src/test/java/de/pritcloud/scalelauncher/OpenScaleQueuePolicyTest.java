package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Set;

public final class OpenScaleQueuePolicyTest {
    private static final String PROFILE =
            "11111111-1111-4111-8111-111111111111";

    @Test
    public void plansOlderSameUserMeasurementsBeforeCurrent() {
        OpenScaleQueuePolicy.Plan plan =
                OpenScaleQueuePolicy.plan(
                        List.of(
                                item(7L, "old-1", 100L, 10L),
                                item(7L, "old-2", 200L, 20L),
                                item(7L, "current", 300L, 30L),
                                item(7L, "newer", 400L, 40L)),
                        7L,
                        PROFILE,
                        "current");

        assertEquals(
                List.of(
                        "old-1",
                        "old-2",
                        "current"),
                ids(plan));
    }

    @Test
    public void neverRetriesDifferentUser() {
        OpenScaleQueuePolicy.Plan plan =
                OpenScaleQueuePolicy.plan(
                        List.of(
                                item(8L, "other-user", 100L, 10L),
                                item(7L, "old", 200L, 20L),
                                item(7L, "current", 300L, 30L)),
                        7L,
                        PROFILE,
                        "current");

        assertEquals(
                List.of(
                        "old",
                        "current"),
                ids(plan));
    }

    @Test
    public void currentSuccessRemovesEntireAttemptedBatch() {
        OpenScaleQueuePolicy.Plan plan =
                planThree();

        assertEquals(
                Set.of(
                        "old-1",
                        "old-2",
                        "current"),
                OpenScaleQueuePolicy.removalsAfterRun(
                        plan,
                        List.of(
                                OpenScaleQueuePolicy.Outcome.STORED,
                                OpenScaleQueuePolicy.Outcome.FAILED,
                                OpenScaleQueuePolicy.Outcome.STORED)));
    }

    @Test
    public void currentFailureKeepsEntireAttemptedBatch() {
        OpenScaleQueuePolicy.Plan plan =
                planThree();

        assertTrue(
                OpenScaleQueuePolicy.removalsAfterRun(
                                plan,
                                List.of(
                                        OpenScaleQueuePolicy.Outcome.STORED,
                                        OpenScaleQueuePolicy.Outcome.FAILED,
                                        OpenScaleQueuePolicy.Outcome.FAILED))
                        .isEmpty());
    }

    @Test
    public void allFailuresRemainQueued() {
        OpenScaleQueuePolicy.Plan plan =
                planThree();

        assertTrue(
                OpenScaleQueuePolicy.removalsAfterRun(
                                plan,
                                List.of(
                                        OpenScaleQueuePolicy.Outcome.FAILED,
                                        OpenScaleQueuePolicy.Outcome.FAILED,
                                        OpenScaleQueuePolicy.Outcome.FAILED))
                        .isEmpty());
    }

    @Test
    public void missingCurrentFailsClosed() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        OpenScaleQueuePolicy.plan(
                                List.of(
                                        item(7L, "old", 100L, 10L)),
                                7L,
                                PROFILE,
                                "current"));
    }

    private static OpenScaleQueuePolicy.Plan planThree() {
        return OpenScaleQueuePolicy.plan(
                List.of(
                        item(7L, "old-1", 100L, 10L),
                        item(7L, "old-2", 200L, 20L),
                        item(7L, "current", 300L, 30L)),
                7L,
                PROFILE,
                "current");
    }

    private static List<String> ids(
            OpenScaleQueuePolicy.Plan plan) {
        return plan.attempts
                .stream()
                .map(
                        item ->
                                item.measurement.measurementId)
                .toList();
    }

    private static OpenScalePendingRoomStore.Item item(
            long userId,
            String measurementId,
            long timestampMs,
            long queuedAtMs) {
        return new OpenScalePendingRoomStore.Item(
                userId,
                PROFILE,
                new S400FinalMeasurement(
                        measurementId,
                        70.0f,
                        510.0f,
                        490.0f,
                        timestampMs,
                        3),
                queuedAtMs);
    }
}
