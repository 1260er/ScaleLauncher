package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PeerCollectorStatusPayloadTest {

    @Test
    public void collectorTrueSurvivesRoundTrip() {
        PeerCollectorStatusPayload original =
                PeerCollectorStatusPayload.create(
                        true);

        PeerCollectorStatusPayload decoded =
                PeerCollectorStatusPayload.decode(
                        original.encode());

        assertNotNull(decoded);
        assertTrue(decoded.collector);
    }

    @Test
    public void collectorFalseSurvivesRoundTrip() {
        PeerCollectorStatusPayload original =
                PeerCollectorStatusPayload.create(
                        false);

        PeerCollectorStatusPayload decoded =
                PeerCollectorStatusPayload.decode(
                        original.encode());

        assertNotNull(decoded);
        assertFalse(decoded.collector);
    }

    @Test
    public void missingCollectorFieldIsRejected() {
        PeerCollectorStatusPayload decoded =
                PeerCollectorStatusPayload.decode(
                        "{\"version\":1,"
                                + "\"type\":\"collector_status\","
                                + "\"messageId\":"
                                + "\"11111111-1111-1111-1111-111111111111\"}");

        assertNull(decoded);
    }
}
