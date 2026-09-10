package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class ScaleScanServiceNotificationIconTest {
    @Test
    public void monitorUsesScaleStatusColorWithoutLargeIcon()
            throws Exception {
        String source = loadServiceSource();

        String monitor =
                block(
                        source,
                        "private Notification monitorNotification(",
                        "private Notification resultNotification(");

        assertTrue(
                monitor.contains(
                        "state.mode == ServiceState.Mode.RUNNING"));

        assertTrue(
                monitor.contains(
                        "state.collectorSource"));

        assertTrue(
                monitor.contains(
                        "!= ServiceState.CollectorSource.NONE"));


        assertTrue(
                monitor.contains(
                        "android.graphics.Color.rgb("));

        assertTrue(
                monitor.contains(
                        "10,"));

        assertTrue(
                monitor.contains(
                        "215,"));

        assertTrue(
                monitor.contains(
                        "135)"));

        assertTrue(
                monitor.contains(
                        "223,"));

        assertTrue(
                monitor.contains(
                        "30,"));

        assertTrue(
                monitor.contains(
                        "72)"));

        assertTrue(
                monitor.contains(
                        ".setColor(scaleIconColor)"));

        assertFalse(
                monitor.contains(
                        ".setLargeIcon("));

        assertFalse(
                monitor.contains(
                        "BitmapFactory.decodeResource("));
    }

    @Test
    public void smallStatusBarIconRemainsAndroidCompatible()
            throws Exception {
        String source = loadServiceSource();

        String monitor =
                block(
                        source,
                        "private Notification monitorNotification(",
                        "private Notification resultNotification(");

        assertTrue(
                monitor.contains(
                        ".setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)"));
    }

    private static String block(
            String source,
            String startMarker,
            String endMarker) {
        int start =
                source.indexOf(
                        startMarker);

        int end =
                source.indexOf(
                        endMarker,
                        start);

        assertTrue(start >= 0);
        assertTrue(end > start);

        return source.substring(
                start,
                end);
    }

    private static String loadServiceSource()
            throws Exception {
        return new String(
                Files.readAllBytes(
                        serviceSource()),
                StandardCharsets.UTF_8);
    }

    private static Path serviceSource() {
        Path modulePath =
                Paths.get(
                        "src/main/java/de/pritcloud/scalelauncher/ScaleScanService.java");

        if (Files.exists(
                modulePath)) {
            return modulePath;
        }

        Path rootPath =
                Paths.get(
                        "app/src/main/java/de/pritcloud/scalelauncher/ScaleScanService.java");

        assertTrue(
                Files.exists(
                        rootPath));

        return rootPath;
    }
}
