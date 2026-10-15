package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class ScaleScanServiceGattGenerationTest {
    @Test
    public void newClientHasGenerationAndAllCallbacksAreGuarded()
            throws Exception {
        String source = loadSource();

        int generation = source.indexOf(
                "final long generation = ++gattClientGeneration;");
        int creation = source.indexOf(
                "gattClient = new S400GattClient(", generation);
        int connect = source.indexOf(
                "gattClient.connect(device, token, true);", creation);

        assertTrue(generation >= 0);
        assertTrue(creation > generation);
        assertTrue(connect > creation);

        String listener = source.substring(creation, connect);

        String[] callbacks = {
                "@Override public void onStateChanged(",
                "@Override public void onAuthenticated()",
                "@Override public void onMeasurement(",
                "@Override public void onDisconnected(",
                "@Override public void onError("
        };

        for (String callback : callbacks) {
            int start = listener.indexOf(callback);
            assertTrue("Callback fehlt: " + callback, start >= 0);

            int next = listener.indexOf(
                    "@Override", start + callback.length());

            String body = listener.substring(
                    start, next >= 0 ? next : listener.length());

            String normalized = body.replaceAll("\\s+", " ");

            assertTrue(
                    "Generation-Guard fehlt am Callback-Anfang: " + callback,
                    normalized.contains(
                            ") { if (generation != gattClientGeneration "
                            + "|| gattClient == null) return;"));
        }
    }

    private static String loadSource() throws Exception {
        Path module = Paths.get(
                "src/main/java/de/pritcloud/scalelauncher/ScaleScanService.java");
        Path repository = Paths.get(
                "app/src/main/java/de/pritcloud/scalelauncher/ScaleScanService.java");

        Path source = Files.exists(module) ? module : repository;
        assertTrue(Files.exists(source));

        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
