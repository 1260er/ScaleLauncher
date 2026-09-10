package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class ScaleScanServicePeerRepairTest {
    @Test
    public void orphanPeerDataIsRepairedBeforePendingRepair()
            throws Exception {
        String source =
                new String(
                        Files.readAllBytes(
                                serviceSource()),
                        StandardCharsets.UTF_8);

        int onCreateStart =
                source.indexOf(
                        "@Override public void onCreate()");

        int onCreateEnd =
                source.indexOf(
                        "@Override public int onStartCommand",
                        onCreateStart);

        assertTrue(onCreateStart >= 0);
        assertTrue(onCreateEnd > onCreateStart);

        String onCreate =
                source.substring(
                        onCreateStart,
                        onCreateEnd);

        int orphanRepair =
                onCreate.indexOf(
                        "repairPeerOrphans();");

        int pendingRepair =
                onCreate.indexOf(
                        "repairPendingAfterPeerChanges();");

        assertTrue(orphanRepair >= 0);
        assertTrue(pendingRepair > orphanRepair);

        int syncRefresh =
                source.indexOf(
                        "refreshTrustedPeerPresence();",
                        onCreateEnd);

        int syncOrphanRepair =
                source.indexOf(
                        "repairPeerOrphans();",
                        syncRefresh);

        int syncPendingRepair =
                source.indexOf(
                        "repairPendingAfterPeerChanges();",
                        syncOrphanRepair);

        assertTrue(syncRefresh >= 0);
        assertTrue(syncOrphanRepair > syncRefresh);
        assertTrue(syncPendingRepair > syncOrphanRepair);

        int repairStart =
                source.indexOf(
                        "private void repairPeerOrphans()");

        int repairEnd =
                source.indexOf(
                        "private void repairPendingAfterPeerChanges()",
                        repairStart);

        assertTrue(repairStart >= 0);
        assertTrue(repairEnd > repairStart);

        String repair =
                source.substring(
                        repairStart,
                        repairEnd);

        assertTrue(
                repair.contains(
                        "PeerTrustRoomStore.load("));

        assertTrue(
                repair.contains(
                        "PeerOutboxRoomStore.load("));

        assertTrue(
                repair.contains(
                        "HouseholdProfileRoomStore.load("));

        assertTrue(
                repair.contains(
                        "RemotePendingMeasurementRoomStore.load("));

        assertTrue(
                repair.contains(
                        "PeerInboxDedupRoomStore.senderDeviceIds("));

        assertTrue(
                repair.contains(
                        "PeerOutboxRoomStore.removePeer("));

        assertTrue(
                repair.contains(
                        "PeerInboxDedupRoomStore.removePeer("));

        assertTrue(
                repair.contains(
                        "HouseholdProfileRoomStore.removeOwner("));

        assertTrue(
                repair.contains(
                        "RemotePendingMeasurementRoomStore.removeCollector("));

        int candidateHelper =
                repair.indexOf(
                        "private static void addPeerOrphanCandidate(");

        assertTrue(candidateHelper >= 0);

        String helper =
                repair.substring(
                        candidateHelper);

        int validDeviceGuard =
                helper.indexOf(
                        "!PeerTrustStore.isValidDeviceId(");

        int localDeviceGuard =
                helper.indexOf(
                        "deviceId.equals(");

        int trustedDeviceGuard =
                helper.indexOf(
                        "trustedDeviceIds.contains(");

        int orphanAdd =
                helper.indexOf(
                        "orphanDeviceIds.add(");

        assertTrue(validDeviceGuard >= 0);
        assertTrue(localDeviceGuard >= 0);
        assertTrue(trustedDeviceGuard >= 0);
        assertTrue(orphanAdd > validDeviceGuard);
        assertTrue(orphanAdd > localDeviceGuard);
        assertTrue(orphanAdd > trustedDeviceGuard);
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
