package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class S400WakeupSignalTest {
    @Test
    public void freshSignalStoredBeforeLogCooldown()
            throws Exception {
        Path path = Paths.get(
                "src/main/java/de/pritcloud/scalelauncher/"
                + "S400WakeupReceiver.java");

        if (!Files.exists(path)) {
            path = Paths.get(
                    "app/src/main/java/de/pritcloud/scalelauncher/"
                    + "S400WakeupReceiver.java");
        }

        String source = new String(
                Files.readAllBytes(path),
                StandardCharsets.UTF_8);

        int throttle = source.indexOf(
                "if (!shouldHandleSignal(");
        int write = source.indexOf(
                "// Keep accepted observations even during log cooldown.");

        int signal = source.indexOf(
                "last_valid_signal_elapsed_ms", write);
        int wall = source.indexOf(
                "last_valid_signal_wall_ms", signal);
        int mac = source.indexOf(
                "last_valid_signal_mac", wall);
        int cooldown = source.indexOf(
                "last_log_elapsed_ms", mac);

        assertTrue(throttle >= 0);
        assertTrue(write > throttle);
        assertTrue(signal > write);
        assertTrue(wall > signal);
        assertTrue(mac > wall);
        assertTrue(cooldown > mac);
    }
    @Test
    public void wakeupScanArmedBeforeRecoveryThreshold()
            throws Exception {
        Path path = Paths.get(
                "src/main/java/de/pritcloud/scalelauncher/ScaleScanService.java");

        if (!Files.exists(path)) {
            path = Paths.get(
                    "app/src/main/java/de/pritcloud/scalelauncher/ScaleScanService.java");
        }

        String source = new String(
                Files.readAllBytes(path),
                StandardCharsets.UTF_8);

        int start = source.indexOf(
                "private void updateS400WakeupScan(");
        int end = source.indexOf(
                "private void observeS400Connecting(", start);

        assertTrue(start >= 0);
        assertTrue(end > start);

        String scanLogic = source.substring(start, end);

        assertTrue(scanLogic.contains(
                "S400GattClient.State.CONNECTING"));
        assertTrue(!scanLogic.contains(
                ">= S400_PROBE_MIN_CONNECTING_MS"));

        int recovery = source.indexOf(
                "private boolean canRecoverS400(");
        int recoveryEnd = source.indexOf(
                "private void stopS400WakeupScan()", recovery);

        assertTrue(recovery >= 0);
        assertTrue(recoveryEnd > recovery);
        assertTrue(source.substring(
                recovery, recoveryEnd).contains(
                "S400_PROBE_MIN_CONNECTING_MS"));
    }

    @Test
    public void matchLostCannotTriggerRecovery()
            throws Exception {
        Path scanPath = Paths.get(
                "src/main/java/de/pritcloud/scalelauncher/S400WakeupScan.java");
        Path receiverPath = Paths.get(
                "src/main/java/de/pritcloud/scalelauncher/S400WakeupReceiver.java");

        if (!Files.exists(scanPath)) {
            scanPath = Paths.get(
                    "app/src/main/java/de/pritcloud/scalelauncher/S400WakeupScan.java");
            receiverPath = Paths.get(
                    "app/src/main/java/de/pritcloud/scalelauncher/S400WakeupReceiver.java");
        }

        String scanSource = new String(
                Files.readAllBytes(scanPath),
                StandardCharsets.UTF_8);

        String receiverSource = new String(
                Files.readAllBytes(receiverPath),
                StandardCharsets.UTF_8);

        assertTrue(scanSource.contains(
                "ScanSettings.CALLBACK_TYPE_FIRST_MATCH"));
        assertTrue(scanSource.contains(
                "| ScanSettings.CALLBACK_TYPE_MATCH_LOST"));

        int guard = receiverSource.indexOf(
                "if ((callbackType & ScanSettings.CALLBACK_TYPE_MATCH_LOST) != 0)");
        int results = receiverSource.indexOf(
                "ArrayList<ScanResult> results;");

        assertTrue(guard >= 0);
        assertTrue(results > guard);
        assertTrue(receiverSource.substring(
                guard, results).contains("return;"));
    }

    @Test
    public void rapidAdvertisementsAreThrottled() {
        assertTrue(S400WakeupReceiver.shouldHandleSignal(
                -1L, 10_000L));

        assertFalse(S400WakeupReceiver.shouldHandleSignal(
                10_000L, 10_500L));

        assertFalse(S400WakeupReceiver.shouldHandleSignal(
                10_000L, 11_999L));

        assertTrue(S400WakeupReceiver.shouldHandleSignal(
                10_000L, 12_000L));
    }

    @Test
    public void elapsedClockResetAllowsNewSignal() {
        assertTrue(S400WakeupReceiver.shouldHandleSignal(
                1_000_000L, 500L));

        assertFalse(S400WakeupReceiver.shouldHandleSignal(
                1_000L, -1L));
    }

    @Test
    public void failedScanStopBlocksNewRegistration()
            throws Exception {
        Path path = Paths.get(
                "src/main/java/de/pritcloud/scalelauncher/ScaleScanService.java");

        if (!Files.exists(path)) {
            path = Paths.get(
                    "app/src/main/java/de/pritcloud/scalelauncher/ScaleScanService.java");
        }

        String source = new String(
                Files.readAllBytes(path),
                StandardCharsets.UTF_8);

        int start = source.indexOf(
                "private void updateS400WakeupScan(");
        int end = source.indexOf(
                "private void observeS400Connecting(", start);

        assertTrue(start >= 0);
        assertTrue(end > start);

        String body = source.substring(start, end);

        int error = body.indexOf(
                "S400-Wakeup: Scanfehler erkannt");
        int stop = body.indexOf(
                "stopS400WakeupScan();", error);
        int guard = body.indexOf(
                "if (s400WakeupScanRegistered", stop);
        int restart = body.indexOf(
                "S400WakeupScan.start(this, mac)");

        assertTrue(error >= 0);
        assertTrue(stop > error);
        assertTrue(guard > stop);
        assertTrue(restart > guard);
        assertTrue(body.substring(guard, restart).contains(
                "s400WakeupScanStopPending"));
        assertTrue(body.substring(guard, restart).contains(
                "return;"));
    }

}
