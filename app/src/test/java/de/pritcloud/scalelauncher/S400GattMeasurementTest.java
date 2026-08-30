package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class S400GattMeasurementTest {
    @Test
    public void parsesKnownFinalMeasurementVector() {
        byte[] csv =
                "0,0,7,723,1,0,1700000000,0,5123,4876"
                        .getBytes(StandardCharsets.US_ASCII);

        byte[] plaintext =
                new byte[csv.length + 3];

        plaintext[0] = 0x01;
        plaintext[1] = (byte) 0xa0;

        System.arraycopy(
                csv,
                0,
                plaintext,
                2,
                csv.length);

        plaintext[plaintext.length - 1] = 0x00;

        S400GattMeasurement measurement =
                S400GattMeasurement.parse(
                        plaintext);

        assertNotNull(measurement);
        assertEquals(
                S400GattMeasurement.Type.FINAL,
                measurement.type);
        assertEquals(
                72.3f,
                measurement.weightKg,
                0.001f);
        assertTrue(measurement.stable);
        assertEquals(
                Integer.valueOf(7),
                measurement.profileId);
        assertEquals(
                Long.valueOf(1_700_000_000L),
                measurement.timestampSeconds);
        assertEquals(
                512.3f,
                measurement.impedance,
                0.001f);
        assertEquals(
                487.6f,
                measurement.impedanceLow,
                0.001f);
    }
}
