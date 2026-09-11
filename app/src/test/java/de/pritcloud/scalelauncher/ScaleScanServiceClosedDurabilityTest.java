package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class ScaleScanServiceClosedDurabilityTest {
    @Test
    public void localClosurePreservesAlreadyQueuedClosedEntries()
            throws Exception {
        String source = loadSource();

        String helper =
                block(
                        source,
                        "private boolean queueMeasurementClosedForLocalRemoval(",
                        "private boolean broadcastMeasurementClosed(");

        int cleanup =
                helper.indexOf(
                        "PeerOutboxRoomStore.removeMeasurementExceptClosed(");

        int broadcast =
                helper.indexOf(
                        "broadcastMeasurementClosed(");

        assertTrue(cleanup >= 0);
        assertTrue(broadcast > cleanup);

        assertFalse(
                helper.contains(
                        "PeerOutboxRoomStore.removeMeasurement("));
    }

    @Test
    public void localDeletionPathsRequireDurableClosed()
            throws Exception {
        String source = loadSource();

        assertGuardBeforeRemoval(
                block(
                        source,
                        "private void removePendingWithoutCandidates(",
                        "private boolean validIncomingDecision("));

        assertGuardBeforeRemoval(
                block(
                        source,
                        "private void repairStaleAmbiguousPending()",
                        "private void repairStoredResolvedPending()"));

        assertGuardBeforeRemoval(
                block(
                        source,
                        "private void repairStoredResolvedPending()",
                        "private void routeMeasurement("));

        assertGuardBeforeRemoval(
                block(
                        source,
                        "private boolean promoteRejectedLocalPendingToRemoteRescue(",
                        "private boolean validSelectablePendingCandidate("));

        assertGuardBeforeRemoval(
                block(
                        source,
                        "private void resolvePendingDecision(",
                        "private String pendingDisplayName("));

        assertGuardBeforeRemoval(
                block(
                        source,
                        "private void assignPending(",
                        "private static final class OpenScalePreparedMeasurement"));
    }

    @Test
    public void rescueRetryRequiresPreviouslyRejectedLocalCandidate()
            throws Exception {
        String source = loadSource();

        String reject =
                block(
                        source,
                        "private void rejectLocalPendingCandidates(",
                        "private boolean promoteRejectedLocalPendingToRemoteRescue(");

        assertTrue(
                reject.contains(
                        "boolean remoteRescueHandled ="));

        assertTrue(
                reject.contains(
                        "promoteRejectedLocalPendingToRemoteRescue("));

        String rescue =
                block(
                        source,
                        "private boolean promoteRejectedLocalPendingToRemoteRescue(",
                        "private boolean validSelectablePendingCandidate(");

        assertTrue(
                rescue.contains(
                        "pending.rejectedProfileIds.contains("));

        assertTrue(
                rescue.contains(
                        "hasRejectedLocalCandidate"));

        assertTrue(
                rescue.contains(
                        "if (!queueMeasurementClosedForLocalRemoval("));

        assertTrue(
                rescue.contains(
                        "return true;"));
    }

    @Test
    public void peerRepairRetriesDeferredRescueBeforeRemoval()
            throws Exception {
        String source = loadSource();

        String repair =
                block(
                        source,
                        "private void repairPendingAfterPeerChanges()",
                        "private void repairStaleAmbiguousPending()");

        int autoResolve =
                repair.indexOf(
                        "autoResolveSingleRemainingCandidate(");

        int rescue =
                repair.indexOf(
                        "promoteRejectedLocalPendingToRemoteRescue(",
                        autoResolve);

        int rescueGuard =
                repair.indexOf(
                        "if (!remoteRescueHandled)",
                        rescue);

        int removal =
                repair.indexOf(
                        "removePendingWithoutCandidates(",
                        rescueGuard);

        assertTrue(autoResolve >= 0);
        assertTrue(rescue > autoResolve);
        assertTrue(rescueGuard > rescue);
        assertTrue(removal > rescueGuard);
    }

    @Test
    public void peerSyncRetriesStoredPendingClosure()
            throws Exception {
        String source = loadSource();

        int action =
                source.indexOf(
                        "ACTION_SYNC_PEERS.equals(");

        int end =
                source.indexOf(
                        "} else {",
                        action);

        assertTrue(action >= 0);
        assertTrue(end > action);

        String sync =
                source.substring(
                        action,
                        end);

        int peerRepair =
                sync.indexOf(
                        "repairPeerOrphans();");

        int pendingRepair =
                sync.indexOf(
                        "repairPendingAfterPeerChanges();");

        int storedRepair =
                sync.indexOf(
                        "repairStoredResolvedPending();");

        assertTrue(peerRepair >= 0);
        assertTrue(pendingRepair > peerRepair);
        assertTrue(storedRepair > pendingRepair);
    }

    @Test
    public void remoteRoutingDefersClosedUntilRouteAck()
            throws Exception {
        String source = loadSource();

        String resolved =
                block(
                        source,
                        "private void resolvePendingDecision(",
                        "private String pendingDisplayName(");

        int routed =
                resolved.indexOf(
                        "if (enqueueRoutedMeasurement(");

        assertTrue(routed >= 0);

        String remote =
                resolved.substring(
                        routed);

        assertFalse(
                remote.contains(
                        "broadcastMeasurementClosed("));

        assertFalse(
                remote.contains(
                        "PendingMeasurementRoomStore.remove("));
    }

    @Test
    public void acceptedPeerDecisionQueuesRouteBeforeDedupAndAck()
            throws Exception {
        String source = loadSource();

        String decision =
                block(
                        source,
                        "if (PeerMeasurementDecisionPayload.TYPE.equals(",
                        "if (PeerClaimPayload.TYPE.equals(type))");

        int accepted =
                decision.indexOf(
                        "if (decision.isAccepted())");

        int route =
                decision.indexOf(
                        "enqueueRoutedMeasurement(",
                        accepted);

        int dedup =
                decision.indexOf(
                        "PeerInboxDedupRoomStore.mark(",
                        accepted);

        int ack =
                decision.indexOf(
                        "queuePeerAck(",
                        accepted);

        assertTrue(accepted >= 0);
        assertTrue(route > accepted);
        assertTrue(dedup > route);
        assertTrue(ack > dedup);
    }

    @Test
    public void peerRejectBatchesOwnedCandidatesBeforeAutoResolve()
            throws Exception {
        String source = loadSource();

        String decision =
                block(
                        source,
                        "if (PeerMeasurementDecisionPayload.TYPE.equals(",
                        "if (PeerClaimPayload.TYPE.equals(type))");

        int reject =
                decision.indexOf(
                        "rejectPendingCandidatesOwnedByPeer(");

        int dedup =
                decision.indexOf(
                        "PeerInboxDedupRoomStore.mark(",
                        reject);

        int autoResolve =
                decision.indexOf(
                        "autoResolveSingleRemainingCandidate(",
                        dedup);

        assertTrue(reject >= 0);
        assertTrue(dedup > reject);
        assertTrue(autoResolve > dedup);

        String helper =
                block(
                        source,
                        "private boolean rejectPendingCandidatesOwnedByPeer(",
                        "private void rejectUnclaimedPeerCandidates(");

        assertTrue(
                helper.contains(
                        "PendingMeasurementRoomStore.rejectCandidates("));
    }

    @Test
    public void routeAckQueuesClosedBeforeDeletingPending()
            throws Exception {
        String source = loadSource();

        String ack =
                block(
                        source,
                        "if (PeerAckPayload.TYPE.equals(type))",
                        "if (PeerProfileManifestPayload.TYPE.equals(type))");

        int routeAck =
                ack.indexOf(
                        "\"route:\"");

        int routePresence =
                ack.indexOf(
                        "PeerOutboxRoomStore.forPeer(",
                        routeAck);

        int exactRouteMatch =
                ack.indexOf(
                        "ack.acknowledgedMessageId.equals(",
                        routePresence);

        int closed =
                ack.indexOf(
                        "broadcastMeasurementClosed(",
                        exactRouteMatch);

        int cleanup =
                ack.indexOf(
                        "PeerOutboxRoomStore.removeMeasurementExceptClosed(",
                        closed);

        int removal =
                ack.indexOf(
                        "PendingMeasurementRoomStore.remove(",
                        cleanup);

        assertTrue(routeAck >= 0);
        assertTrue(routePresence > routeAck);
        assertTrue(exactRouteMatch > routePresence);
        assertTrue(closed > exactRouteMatch);
        assertTrue(cleanup > closed);
        assertTrue(removal > cleanup);
    }

    @Test
    public void peerRepairRequeuesResolvedRemotePending()
            throws Exception {
        String source = loadSource();

        String repair =
                block(
                        source,
                        "private void repairPendingAfterPeerChanges()",
                        "private void repairStaleAmbiguousPending()");

        int resolved =
                repair.indexOf(
                        "if (current.isResolved())");

        int route =
                repair.indexOf(
                        "enqueueRoutedMeasurement(",
                        resolved);

        assertTrue(resolved >= 0);
        assertTrue(route > resolved);
    }

    private static void assertGuardBeforeRemoval(
            String block) {
        int guard =
                block.indexOf(
                        "if (!queueMeasurementClosedForLocalRemoval(");

        int removal =
                block.indexOf(
                        "PendingMeasurementRoomStore.remove(");

        assertTrue(guard >= 0);
        assertTrue(removal > guard);
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

    private static String loadSource()
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
