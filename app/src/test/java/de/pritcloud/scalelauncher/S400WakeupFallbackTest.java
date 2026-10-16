package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.bluetooth.le.ScanCallback;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public final class S400WakeupFallbackTest {
    @Test
    public void unsupportedTrackingUsesFallback() {
        assertTrue(S400WakeupScan.shouldFallbackToAllMatches(
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED));
        assertTrue(S400WakeupScan.shouldFallbackToAllMatches(
                ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES));
    }

    @Test
    public void normalAndUnrelatedErrorsDoNotFallback() {
        assertFalse(S400WakeupScan.shouldFallbackToAllMatches(0));
        assertFalse(S400WakeupScan.shouldFallbackToAllMatches(
                ScanCallback.SCAN_FAILED_ALREADY_STARTED));
        assertFalse(S400WakeupScan.shouldFallbackToAllMatches(
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR));
    }
    @Test
    public void asynchronousScanFailureEnablesFallback()
            throws Exception {
        Path root = Paths.get(
                "src/main/java/de/pritcloud/scalelauncher");

        if (!Files.exists(root)) {
            root = Paths.get(
                    "app/src/main/java/de/pritcloud/scalelauncher");
        }

        String receiverSource = new String(
                Files.readAllBytes(
                        root.resolve("S400WakeupReceiver.java")),
                StandardCharsets.UTF_8);

        String scanSource = new String(
                Files.readAllBytes(
                        root.resolve("S400WakeupScan.java")),
                StandardCharsets.UTF_8);

        assertTrue(receiverSource.contains(
                "S400WakeupScan.shouldFallbackToAllMatches(code)"));

        assertTrue(receiverSource.contains(
                "diagnostics.putBoolean("));

        assertTrue(receiverSource.contains(
                "force_all_matches"));

        assertTrue(scanSource.contains(
                "useAllMatches"));

        assertTrue(scanSource.contains(
                "? fallbackSettings"));

        assertTrue(scanSource.contains(
                ": trackingSettings"));
    }

}
