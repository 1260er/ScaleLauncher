package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class ScaleScanServicePeerRetryTest {
    @Test
    public void missingAckUsesSeparateTenSecondRetry()
            throws Exception {
        String source =
                new String(
                        Files.readAllBytes(
                                serviceSource()),
                        StandardCharsets.UTF_8);

        assertTrue(
                source.contains(
                        "private static final long PEER_SYNC_RETRY_MS = 30_000L;"));

        assertTrue(
                source.contains(
                        "private static final long PEER_ACK_RETRY_MS = 10_000L;"));

        int sentStart =
                source.indexOf(
                        "public void onMessageSent(");

        int sentEnd =
                source.indexOf(
                        "public void onPeerPresence(",
                        sentStart);

        assertTrue(sentStart >= 0);
        assertTrue(sentEnd > sentStart);

        String sentBlock =
                source.substring(
                        sentStart,
                        sentEnd);

        int directRetry =
                sentBlock.indexOf(
                        "250L);");

        int ackRetry =
                sentBlock.indexOf(
                        "PEER_ACK_RETRY_MS);");

        int oldRetry =
                sentBlock.indexOf(
                        "PEER_SYNC_RETRY_MS);");

        assertTrue(directRetry >= 0);
        assertTrue(ackRetry > directRetry);
        assertTrue(oldRetry < 0);
    }

    @Test
    public void transportErrorsStillUseBackoffPolicy()
            throws Exception {
        String source =
                new String(
                        Files.readAllBytes(
                                serviceSource()),
                        StandardCharsets.UTF_8);

        int errorStart =
                source.indexOf(
                        "public void onError(");

        int errorEnd =
                source.indexOf(
                        "private void registerBluetoothStateReceiver()",
                        errorStart);

        assertTrue(errorStart >= 0);
        assertTrue(errorEnd > errorStart);

        String errorBlock =
                source.substring(
                        errorStart,
                        errorEnd);

        assertTrue(
                errorBlock.contains(
                        "peerErrorRetryDelayMs();"));

        assertTrue(
                errorBlock.contains(
                        "retryDelayMs);"));

        assertFalse(
                errorBlock.contains(
                        "PEER_ACK_RETRY_MS"));

        int policyStart =
                source.indexOf(
                        "private long peerErrorRetryDelayMs()");

        int policyEnd =
                source.indexOf(
                        "private void schedulePeerSync(",
                        policyStart);

        assertTrue(policyStart >= 0);
        assertTrue(policyEnd > policyStart);

        String policyBlock =
                source.substring(
                        policyStart,
                        policyEnd);

        assertTrue(
                policyBlock.contains(
                        "PeerRetryPolicy.delayMs("));

        assertFalse(
                policyBlock.contains(
                        "PEER_ACK_RETRY_MS"));
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
