package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class ScaleScanServicePostOpenScaleTest {
    @Test
    public void referenceWeightFailureDoesNotRejectStoredMeasurement()
            throws Exception {
        String source =
                new String(
                        Files.readAllBytes(
                                serviceSource()),
                        StandardCharsets.UTF_8);

        int start =
                source.indexOf(
                        "private void completeMeasurementAfterOpenScale(");

        int end =
                source.indexOf(
                        "private static final class OpenScaleWriteAttempt",
                        start);

        assertTrue(start >= 0);
        assertTrue(end > start);

        String block =
                source.substring(
                        start,
                        end);

        int catchIndex =
                block.indexOf(
                        "catch (RuntimeException exception)");

        int healthConnectIndex =
                block.indexOf(
                        "boolean healthConnectStarted");

        assertTrue(catchIndex >= 0);
        assertTrue(healthConnectIndex > catchIndex);

        String failurePath =
                block.substring(
                        catchIndex,
                        healthConnectIndex);

        assertFalse(
                failurePath.contains(
                        "rejectMeasurement("));

        assertFalse(
                failurePath.contains(
                        "return;"));

        assertTrue(
                failurePath.contains(
                        "log_reference_weight_update_failed"));
    }

    private static Path serviceSource() {
        Path modulePath =
                Paths.get(
                        "src/main/java/de/pritcloud/scalelauncher/ScaleScanService.java");

        if (Files.exists(modulePath)) {
            return modulePath;
        }

        Path rootPath =
                Paths.get(
                        "app/src/main/java/de/pritcloud/scalelauncher/ScaleScanService.java");

        assertTrue(
                Files.exists(rootPath));

        return rootPath;
    }
}
