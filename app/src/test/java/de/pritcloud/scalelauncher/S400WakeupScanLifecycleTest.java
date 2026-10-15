package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class S400WakeupScanLifecycleTest {
    private static String stopMethod() throws IOException {
        Path path = Paths.get(
                "src/main/java/de/pritcloud/scalelauncher/S400WakeupScan.java");

        if (!Files.exists(path)) {
            path = Paths.get(
                    "app/src/main/java/de/pritcloud/scalelauncher/S400WakeupScan.java");
        }

        String source = new String(
                Files.readAllBytes(path),
                StandardCharsets.UTF_8);

        int begin = source.indexOf(
                "static boolean stop(Context context)");
        int end = source.indexOf(
                "private static PendingIntent pendingIntent(");

        assertTrue(begin >= 0);
        assertTrue(end > begin);

        return source.substring(begin, end);
    }

    @Test
    public void unavailableBluetoothKeepsPendingIntent()
            throws IOException {
        String body = stopMethod();

        int unavailable = body.indexOf(
                "if (scanner == null)");
        int tryBlock = body.indexOf(
                "try {", unavailable);

        assertTrue(unavailable >= 0);
        assertTrue(tryBlock > unavailable);

        String branch = body.substring(
                unavailable, tryBlock);

        assertTrue(branch.contains("return false;"));
        assertFalse(branch.contains(
                "callbackIntent.cancel()"));
    }

    @Test
    public void pendingIntentCancelledOnlyAfterSuccessfulStop()
            throws IOException {
        String body = stopMethod();

        int stop = body.indexOf(
                "scanner.stopScan(callbackIntent);");
        int cancel = body.indexOf(
                "callbackIntent.cancel();");

        assertTrue(stop >= 0);
        assertTrue(cancel > stop);
        assertFalse(body.contains("finally {"));
        assertTrue(body.indexOf(
                "callbackIntent.cancel();", cancel + 1) < 0);
    }
    @Test
    public void serviceRetainsRegistrationOnFailedStop()
            throws IOException {
        Path path = Paths.get(
                "src/main/java/de/pritcloud/scalelauncher/ScaleScanService.java");

        if (!Files.exists(path)) {
            path = Paths.get(
                    "app/src/main/java/de/pritcloud/scalelauncher/ScaleScanService.java");
        }

        String source = new String(
                Files.readAllBytes(path),
                StandardCharsets.UTF_8);

        int begin = source.indexOf(
                "private void stopS400WakeupScan()");
        int end = source.indexOf(
                "private void updateS400WakeupScan(", begin);

        assertTrue(begin >= 0);
        assertTrue(end > begin);

        String body = source.substring(begin, end);

        int failed = body.indexOf("if (!stopped)");
        int cleared = body.indexOf(
                "s400WakeupScanRegistered = false;");

        assertTrue(body.contains(
                "boolean stopped = S400WakeupScan.stop(this);"));
        assertTrue(failed >= 0);
        assertTrue(cleared > failed);
        assertTrue(body.substring(
                failed, cleared).contains("return;"));
    }

    @Test
    public void missingRegistrationCountsAsStopped()
            throws IOException {
        String body = stopMethod();

        int missing = body.indexOf(
                "if (callbackIntent == null)");
        int scanner = body.indexOf(
                "BluetoothLeScanner scanner = scanner(context)");

        assertTrue(missing >= 0);
        assertTrue(scanner > missing);
        assertTrue(body.substring(
                missing, scanner).contains("return true;"));
    }

    @Test
    public void processRestartPreservesFailedCleanup()
            throws IOException {
        Path path = Paths.get(
                "src/main/java/de/pritcloud/scalelauncher/ScaleScanService.java");

        if (!Files.exists(path)) {
            path = Paths.get(
                    "app/src/main/java/de/pritcloud/scalelauncher/ScaleScanService.java");
        }

        String source = new String(
                Files.readAllBytes(path),
                StandardCharsets.UTF_8);

        int create = source.indexOf(
                "@Override public void onCreate()");
        int start = source.indexOf(
                "@Override public int onStartCommand(", create);

        assertTrue(create >= 0);
        assertTrue(start > create);

        String body = source.substring(create, start);

        assertTrue(body.contains(
                "s400WakeupScanStopPending ="));
        assertTrue(body.contains(
                "!S400WakeupScan.stop(this);"));

        int stopMethod = source.indexOf(
                "private void stopS400WakeupScan()");
        int updateMethod = source.indexOf(
                "private void updateS400WakeupScan(", stopMethod);

        assertTrue(stopMethod >= 0);
        assertTrue(updateMethod > stopMethod);

        String stopBody = source.substring(
                stopMethod, updateMethod);

        assertTrue(stopBody.contains(
                "&& !s400WakeupScanStopPending"));
    }

    @Test
    public void pendingStopRetriedBeforeNewRegistration()
            throws IOException {
        Path path = Paths.get(
                "src/main/java/de/pritcloud/scalelauncher/ScaleScanService.java");

        if (!Files.exists(path)) {
            path = Paths.get(
                    "app/src/main/java/de/pritcloud/scalelauncher/ScaleScanService.java");
        }

        String source = new String(
                Files.readAllBytes(path),
                StandardCharsets.UTF_8);

        assertTrue(source.contains(
                "private boolean s400WakeupScanStopPending;"));

        int bluetoothStart = source.indexOf(
                "if (state == BluetoothAdapter.STATE_ON)");
        int bluetoothEnd = source.indexOf(
                "private boolean bluetoothStateReceiverRegistered");

        assertTrue(bluetoothStart >= 0);
        assertTrue(bluetoothEnd > bluetoothStart);

        String bluetooth = source.substring(
                bluetoothStart, bluetoothEnd);

        assertTrue(bluetooth.contains(
                "if (s400WakeupScanStopPending)"));
        assertTrue(bluetooth.contains(
                "stopS400WakeupScan();"));

        int updateStart = source.indexOf(
                "private void updateS400WakeupScan(");
        int updateEnd = source.indexOf(
                "private void observeS400Connecting(", updateStart);

        assertTrue(updateStart >= 0);
        assertTrue(updateEnd > updateStart);

        String update = source.substring(
                updateStart, updateEnd);

        int retry = update.indexOf(
                "if (s400WakeupScanStopPending)");
        int registration = update.indexOf(
                "S400WakeupScan.start(this, mac)");

        assertTrue(retry >= 0);
        assertTrue(registration > retry);
        assertTrue(update.substring(
                retry, registration).contains(
                "stopS400WakeupScan();"));
    }

}
