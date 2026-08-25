package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

public final class PeerMeasurementPayloadTest {
    private static final String SCALE_MAC =
            "04:AE:47:67:4E:07";

    private static final String PROFILE_A =
            "11111111-1111-1111-1111-111111111111";

    private static final String PROFILE_B =
            "22222222-2222-2222-2222-222222222222";

    @Test
    public void normalClaimKeepsOriginalMeasurementId() {
        S400FinalMeasurement measurement =
                measurement(
                        "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb");

        PeerMeasurementPayload payload =
                PeerMeasurementPayload.forClaim(
                        SCALE_MAC,
                        measurement,
                        List.of(PROFILE_A));

        assertTrue(payload.requiresClaim);
        assertFalse(payload.manualRescue);
        assertEquals(
                measurement.measurementId,
                payload.transportMessageId());
        assertEquals(
                List.of(PROFILE_A),
                payload.candidateProfileIds);
    }

    @Test
    public void manualRescueUsesSeparateTransportId() {
        S400FinalMeasurement measurement =
                measurement(
                        "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb");

        PeerMeasurementPayload payload =
                PeerMeasurementPayload.forManualRescue(
                        SCALE_MAC,
                        measurement,
                        List.of(PROFILE_A));

        assertTrue(payload.requiresClaim);
        assertTrue(payload.manualRescue);
        assertEquals(
                "rescue:" + measurement.measurementId,
                payload.transportMessageId());
    }

    @Test
    public void routedMeasurementUsesRouteTransportId() {
        S400FinalMeasurement measurement =
                measurement(
                        "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb");

        PeerMeasurementPayload payload =
                PeerMeasurementPayload.forUniqueTarget(
                        SCALE_MAC,
                        measurement,
                        PROFILE_A);

        assertFalse(payload.requiresClaim);
        assertFalse(payload.manualRescue);
        assertEquals(
                "route:" + measurement.measurementId,
                payload.transportMessageId());
        assertEquals(
                PROFILE_A,
                payload.targetProfileId);
        assertTrue(
                payload.candidateProfileIds.isEmpty());
    }

    @Test
    public void claimRejectsDuplicateCandidateIds() {
        S400FinalMeasurement measurement =
                measurement(
                        "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb");

        try {
            PeerMeasurementPayload.forClaim(
                    SCALE_MAC,
                    measurement,
                    List.of(
                            PROFILE_A,
                            PROFILE_A));
            fail("Duplicate profile IDs must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void claimPreservesDistinctCandidateIds() {
        S400FinalMeasurement measurement =
                measurement(
                        "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb");

        PeerMeasurementPayload payload =
                PeerMeasurementPayload.forClaim(
                        SCALE_MAC,
                        measurement,
                        List.of(
                                PROFILE_A,
                                PROFILE_B));

        assertEquals(
                List.of(
                        PROFILE_A,
                        PROFILE_B),
                payload.candidateProfileIds);
    }

    private static S400FinalMeasurement measurement(
            String measurementId) {
        return new S400FinalMeasurement(
                measurementId,
                70.7f,
                510.0f,
                490.0f,
                1_700_000_000_000L,
                null);
    }
}
