package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class S400BodyCompositionTest {
    @Test
    public void computesKnownMaleReferenceVector() {
        S400BodyComposition.Result result =
                S400BodyComposition.compute(
                        new S400BodyComposition.Inputs(
                                40,
                                true,
                                180.0f,
                                80.0f,
                                500.0f,
                                600.0f));

        assertEquals(
                S400BodyComposition.Reliability.OK,
                result.reliability);
        assertFalse(result.impedanceLabelsSwapped);
        assertEquals(24.691f, result.bmi, 0.01f);
        assertEquals(42.109f, result.totalBodyWaterKg, 0.01f);
        assertEquals(57.526f, result.fatFreeMassKg, 0.01f);
        assertEquals(28.092f, result.bodyFatPercent, 0.01f);
        assertEquals(0.421f, result.extracellularToTotalWaterRatio, 0.01f);
    }

    @Test
    public void computesKnownFemaleReferenceVector() {
        S400BodyComposition.Result result =
                S400BodyComposition.compute(
                        new S400BodyComposition.Inputs(
                                40,
                                false,
                                165.0f,
                                65.0f,
                                600.0f,
                                700.0f));

        assertEquals(
                S400BodyComposition.Reliability.OK,
                result.reliability);
        assertFalse(result.impedanceLabelsSwapped);
        assertEquals(23.875f, result.bmi, 0.01f);
        assertEquals(29.463f, result.totalBodyWaterKg, 0.01f);
        assertEquals(40.249f, result.fatFreeMassKg, 0.01f);
        assertEquals(38.078f, result.bodyFatPercent, 0.01f);
        assertEquals(0.440f, result.extracellularToTotalWaterRatio, 0.01f);
    }

    @Test
    public void normalizesSwappedImpedanceLabels() {
        S400BodyComposition.Result normal =
                S400BodyComposition.compute(
                        new S400BodyComposition.Inputs(
                                40,
                                true,
                                180.0f,
                                80.0f,
                                500.0f,
                                600.0f));

        S400BodyComposition.Result swapped =
                S400BodyComposition.compute(
                        new S400BodyComposition.Inputs(
                                40,
                                true,
                                180.0f,
                                80.0f,
                                600.0f,
                                500.0f));

        assertFalse(normal.impedanceLabelsSwapped);
        assertTrue(swapped.impedanceLabelsSwapped);

        assertEquals(
                normal.totalBodyWaterKg,
                swapped.totalBodyWaterKg,
                0.001f);

        assertEquals(
                normal.bodyFatPercent,
                swapped.bodyFatPercent,
                0.001f);
    }

    @Test
    public void marksContactUnreliableOnlyBelowOnePercentDifference() {
        S400BodyComposition.Result belowBoundary =
                S400BodyComposition.compute(
                        new S400BodyComposition.Inputs(
                                40,
                                true,
                                180.0f,
                                80.0f,
                                500.0f,
                                504.0f));

        S400BodyComposition.Result atBoundary =
                S400BodyComposition.compute(
                        new S400BodyComposition.Inputs(
                                40,
                                true,
                                180.0f,
                                80.0f,
                                500.0f,
                                505.0f));

        assertEquals(
                S400BodyComposition.Reliability.UNRELIABLE,
                belowBoundary.reliability);

        assertNull(belowBoundary.totalBodyWaterKg);
        assertNull(belowBoundary.bodyFatPercent);

        assertEquals(
                S400BodyComposition.Reliability.OK,
                atBoundary.reliability);
    }

    @Test
    public void returnsNotAvailableOutsideValidationRange() {
        S400BodyComposition.Result result =
                S400BodyComposition.compute(
                        new S400BodyComposition.Inputs(
                                17,
                                true,
                                180.0f,
                                80.0f,
                                500.0f,
                                600.0f));

        assertEquals(
                S400BodyComposition.Reliability.NOT_AVAILABLE,
                result.reliability);

        assertNull(result.totalBodyWaterKg);
        assertNull(result.fatFreeMassKg);
        assertNull(result.bodyFatPercent);
        assertFalse(result.impedanceLabelsSwapped);
    }
}
