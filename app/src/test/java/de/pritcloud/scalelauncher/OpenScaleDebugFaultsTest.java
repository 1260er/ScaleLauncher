package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

public final class OpenScaleDebugFaultsTest {
    @Test
    public void releaseBuildNeverConsumesMarker()
            throws Exception {
        File cache =
                Files.createTempDirectory(
                        "openscale-fault")
                        .toFile();

        File once =
                new File(
                        cache,
                        OpenScaleDebugFaults.FAIL_ONCE);

        assertTrue(
                once.createNewFile());

        assertFalse(
                OpenScaleDebugFaults.consume(
                        cache,
                        false));

        assertTrue(
                once.isFile());
    }

    @Test
    public void failOnceIsConsumedExactlyOnce()
            throws Exception {
        File cache =
                Files.createTempDirectory(
                        "openscale-fault")
                        .toFile();

        File once =
                new File(
                        cache,
                        OpenScaleDebugFaults.FAIL_ONCE);

        assertTrue(
                once.createNewFile());

        assertTrue(
                OpenScaleDebugFaults.consume(
                        cache,
                        true));

        assertFalse(
                OpenScaleDebugFaults.consume(
                        cache,
                        true));

        assertFalse(
                once.exists());
    }

    @Test
    public void failTwiceIsConsumedExactlyTwice()
            throws Exception {
        File cache =
                Files.createTempDirectory(
                        "openscale-fault")
                        .toFile();

        File twice =
                new File(
                        cache,
                        OpenScaleDebugFaults.FAIL_TWICE);

        assertTrue(
                twice.createNewFile());

        assertTrue(
                OpenScaleDebugFaults.consume(
                        cache,
                        true));

        assertTrue(
                OpenScaleDebugFaults.consume(
                        cache,
                        true));

        assertFalse(
                OpenScaleDebugFaults.consume(
                        cache,
                        true));
    }
}
