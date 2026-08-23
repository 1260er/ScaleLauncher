package de.pritcloud.scalelauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PeerPairingActivity extends Activity {
    private static final int REQ_NEARBY_PERMISSIONS = 401;
    private String serviceId;

    private ConnectionsClient client;
    private PeerEndpointInfo localEndpoint;

    private TextView localInfo;
    private TextView trustedInfo;
    private TextView status;
    private Button startButton;

    private final Map<String, PeerEndpointInfo> discoveredPeers =
            new HashMap<>();

    private final Map<String, PairingCandidate> candidates =
            new HashMap<>();

    private final Set<String> requestedEndpoints =
            new HashSet<>();

    private String activeEndpointId;
    private boolean pairingActive;

    private static final class PairingCandidate {
        final PeerEndpointInfo peer;
        final byte[] sharedSecret;
        boolean acceptedByUser;

        PairingCandidate(PeerEndpointInfo peer,
                         byte[] sharedSecret) {
            this.peer = peer;
            this.sharedSecret = sharedSecret;
        }
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_peer_pairing);

        View contentRoot =
                findViewById(android.R.id.content);

        contentRoot.setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    android.graphics.Insets safeInsets =
                            insets.getInsets(
                                    android.view.WindowInsets.Type.systemBars()
                                            | android.view.WindowInsets.Type.displayCutout());

                    view.setPadding(
                            safeInsets.left,
                            safeInsets.top,
                            safeInsets.right,
                            safeInsets.bottom);

                    return insets;
                });

        serviceId = getPackageName() + ".peer.v1";
        client = Nearby.getConnectionsClient(this);
        localEndpoint = PeerEndpointInfo.local(this);

        localInfo = findViewById(R.id.peerLocalInfo);
        trustedInfo = findViewById(R.id.peerTrustedInfo);
        status = findViewById(R.id.peerPairingStatus);
        startButton = findViewById(R.id.startPeerPairing);

        startButton.setOnClickListener(
                view -> ensurePermissionsAndStart());

        refreshSummary();
    }

    private void refreshSummary() {
        localInfo.setText(
                getString(
                        R.string.peer_local_device,
                        localEndpoint.label));

        trustedInfo.setText(
                getResources().getQuantityString(
                        R.plurals.peer_trusted_count,
                        PeerTrustStore.count(this),
                        PeerTrustStore.count(this)));
    }

    private void ensurePermissionsAndStart() {
        List<String> missing = new ArrayList<>();

        addIfMissing(
                missing,
                Manifest.permission.BLUETOOTH_SCAN);

        addIfMissing(
                missing,
                Manifest.permission.BLUETOOTH_CONNECT);

        addIfMissing(
                missing,
                Manifest.permission.BLUETOOTH_ADVERTISE);

        if (Build.VERSION.SDK_INT <= 31) {
            addIfMissing(
                    missing,
                    Manifest.permission.ACCESS_FINE_LOCATION);
        } else {
            addIfMissing(
                    missing,
                    Manifest.permission.NEARBY_WIFI_DEVICES);
        }

        if (!missing.isEmpty()) {
            requestPermissions(
                    missing.toArray(new String[0]),
                    REQ_NEARBY_PERMISSIONS);
            return;
        }

        startPairing();
    }

    private void addIfMissing(List<String> list,
                              String permission) {
        if (checkSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED) {
            list.add(permission);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults);

        if (requestCode != REQ_NEARBY_PERMISSIONS) {
            return;
        }

        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                status.setText(
                        R.string.peer_permission_missing);
                return;
            }
        }

        startPairing();
    }

    private void startPairing() {
        if (pairingActive) return;

        pairingActive = true;
        activeEndpointId = null;
        requestedEndpoints.clear();
        discoveredPeers.clear();
        candidates.clear();

        startButton.setEnabled(false);
        status.setText(R.string.peer_searching);

        client.stopAdvertising();
        client.stopDiscovery();
        client.stopAllEndpoints();

        AdvertisingOptions advertisingOptions =
                new AdvertisingOptions.Builder()
                        .setStrategy(Strategy.P2P_CLUSTER)
                        .build();

        DiscoveryOptions discoveryOptions =
                new DiscoveryOptions.Builder()
                        .setStrategy(Strategy.P2P_CLUSTER)
                        .build();

        client.startAdvertising(
                        localEndpoint.encode(),
                        serviceId,
                        connectionLifecycleCallback,
                        advertisingOptions)
                .addOnFailureListener(
                        exception -> showFailure(exception));

        client.startDiscovery(
                        serviceId,
                        endpointDiscoveryCallback,
                        discoveryOptions)
                .addOnFailureListener(
                        exception -> showFailure(exception));
    }

    private final EndpointDiscoveryCallback
            endpointDiscoveryCallback =
            new EndpointDiscoveryCallback() {
                @Override
                public void onEndpointFound(
                        String endpointId,
                        DiscoveredEndpointInfo info) {
                    PeerEndpointInfo peer =
                            PeerEndpointInfo.decode(
                                    info.getEndpointInfo());

                    if (peer == null
                            || peer.deviceId.equals(
                                    localEndpoint.deviceId)) {
                        return;
                    }

                    discoveredPeers.put(
                            endpointId,
                            peer);

                    if (PeerTrustStore.isTrusted(
                            PeerPairingActivity.this,
                            peer.deviceId)) {
                        runOnUiThread(
                                () -> status.setText(
                                        getString(
                                                R.string.peer_already_trusted,
                                                displayLabel(peer))));
                        return;
                    }

                    runOnUiThread(
                            () -> status.setText(
                                    getString(
                                            R.string.peer_found,
                                            displayLabel(peer))));

                    /*
                     * Both phones advertise and discover.
                     * Only the lexicographically smaller persistent
                     * device ID starts the connection. This prevents
                     * two simultaneous connection requests.
                     */
                    if (localEndpoint.deviceId.compareTo(
                            peer.deviceId) >= 0) {
                        return;
                    }

                    if (!requestedEndpoints.add(endpointId)) {
                        return;
                    }

                    client.requestConnection(
                                    localEndpoint.encode(),
                                    endpointId,
                                    connectionLifecycleCallback)
                            .addOnSuccessListener(
                                    unused -> runOnUiThread(
                                            () -> status.setText(
                                                    getString(
                                                            R.string.peer_request_sent,
                                                            displayLabel(peer)))))
                            .addOnFailureListener(
                                    exception -> {
                                        requestedEndpoints.remove(
                                                endpointId);
                                        showFailure(exception);
                                    });
                }

                @Override
                public void onEndpointLost(
                        String endpointId) {
                    discoveredPeers.remove(endpointId);
                    requestedEndpoints.remove(endpointId);
                }
            };

    private final ConnectionLifecycleCallback
            connectionLifecycleCallback =
            new ConnectionLifecycleCallback() {
                @Override
                public void onConnectionInitiated(
                        String endpointId,
                        ConnectionInfo info) {
                    PeerEndpointInfo peer =
                            PeerEndpointInfo.decode(
                                    info.getEndpointInfo());

                    if (peer == null
                            || peer.deviceId.equals(
                                    localEndpoint.deviceId)) {
                        client.rejectConnection(endpointId);
                        return;
                    }

                    discoveredPeers.put(
                            endpointId,
                            peer);

                    if (PeerTrustStore.isTrusted(
                            PeerPairingActivity.this,
                            peer.deviceId)) {
                        client.rejectConnection(endpointId);

                        runOnUiThread(
                                () -> status.setText(
                                        getString(
                                                R.string.peer_already_trusted,
                                                displayLabel(peer))));
                        return;
                    }

                    if (activeEndpointId != null
                            && !activeEndpointId.equals(
                                    endpointId)) {
                        client.rejectConnection(endpointId);
                        return;
                    }

                    byte[] rawToken =
                            info.getRawAuthenticationToken();

                    if (rawToken == null
                            || rawToken.length == 0) {
                        client.rejectConnection(endpointId);

                        runOnUiThread(
                                () -> status.setText(
                                        R.string.peer_authentication_failed));
                        return;
                    }

                    byte[] sharedSecret;

                    try {
                        sharedSecret =
                                deriveSharedSecret(
                                        rawToken,
                                        peer.deviceId);
                    } catch (RuntimeException exception) {
                        client.rejectConnection(endpointId);
                        showFailure(exception);
                        return;
                    }

                    PairingCandidate candidate =
                            new PairingCandidate(
                                    peer,
                                    sharedSecret);

                    candidates.put(
                            endpointId,
                            candidate);

                    activeEndpointId = endpointId;

                    String digits =
                            info.getAuthenticationDigits();

                    runOnUiThread(
                            () -> showAuthenticationDialog(
                                    endpointId,
                                    candidate,
                                    digits));
                }

                @Override
                public void onConnectionResult(
                        String endpointId,
                        ConnectionResolution resolution) {
                    PairingCandidate candidate =
                            candidates.get(endpointId);

                    if (candidate == null) {
                        return;
                    }

                    if (!resolution.getStatus().isSuccess()
                            || !candidate.acceptedByUser) {
                        candidates.remove(endpointId);

                        if (endpointId.equals(
                                activeEndpointId)) {
                            activeEndpointId = null;
                        }

                        runOnUiThread(
                                () -> {
                                    status.setText(
                                            R.string.peer_pairing_failed);
                                    pairingActive = false;
                                    startButton.setEnabled(true);
                                });

                        return;
                    }

                    PeerTrustStore.trust(
                            PeerPairingActivity.this,
                            candidate.peer.deviceId,
                            candidate.peer.label,
                            candidate.sharedSecret);

                    client.stopAdvertising();
                    client.stopDiscovery();

                    pairingActive = false;

                    runOnUiThread(
                            () -> {
                                refreshSummary();

                                status.setText(
                                        getString(
                                                R.string.peer_pairing_success,
                                                displayLabel(
                                                        candidate.peer)));

                                startButton.setEnabled(true);
                            });
                }

                @Override
                public void onDisconnected(
                        String endpointId) {
                    candidates.remove(endpointId);
                    requestedEndpoints.remove(endpointId);

                    if (endpointId.equals(
                            activeEndpointId)) {
                        activeEndpointId = null;
                    }
                }
            };

    private void showAuthenticationDialog(
            String endpointId,
            PairingCandidate candidate,
            String digits) {
        String code =
                digits == null || digits.isBlank()
                        ? "----"
                        : digits;

        new AlertDialog.Builder(this)
                .setTitle(
                        R.string.peer_verify_title)
                .setMessage(
                        getString(
                                R.string.peer_verify_message,
                                displayLabel(candidate.peer),
                                code))
                .setPositiveButton(
                        R.string.peer_verify_accept,
                        (dialog, which) -> {
                            candidate.acceptedByUser = true;

                            client.acceptConnection(
                                            endpointId,
                                            payloadCallback)
                                    .addOnFailureListener(
                                            exception -> {
                                                candidate.acceptedByUser =
                                                        false;
                                                showFailure(exception);
                                            });
                        })
                .setNegativeButton(
                        R.string.peer_verify_reject,
                        (dialog, which) -> {
                            candidate.acceptedByUser = false;
                            client.rejectConnection(endpointId);
                            activeEndpointId = null;
                        })
                .setOnCancelListener(
                        dialog -> {
                            candidate.acceptedByUser = false;
                            client.rejectConnection(endpointId);
                            activeEndpointId = null;
                        })
                .show();
    }

    private final PayloadCallback payloadCallback =
            new PayloadCallback() {
                @Override
                public void onPayloadReceived(
                        String endpointId,
                        Payload payload) {
                    // Stage 3b only establishes trust.
                }

                @Override
                public void onPayloadTransferUpdate(
                        String endpointId,
                        PayloadTransferUpdate update) {
                    // No payloads are transferred during Stage 3b.
                }
            };

    private byte[] deriveSharedSecret(
            byte[] rawAuthenticationToken,
            String remoteDeviceId) {
        try {
            String first;
            String second;

            if (localEndpoint.deviceId.compareTo(
                    remoteDeviceId) < 0) {
                first = localEndpoint.deviceId;
                second = remoteDeviceId;
            } else {
                first = remoteDeviceId;
                second = localEndpoint.deviceId;
            }

            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            digest.update(
                    "ScaleLauncher-peer-trust-v1"
                            .getBytes(
                                    StandardCharsets.UTF_8));

            digest.update(
                    first.getBytes(
                            StandardCharsets.UTF_8));

            digest.update(
                    second.getBytes(
                            StandardCharsets.UTF_8));

            digest.update(rawAuthenticationToken);

            return digest.digest();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not derive peer trust secret",
                    exception);
        }
    }

    private String displayLabel(
            PeerEndpointInfo peer) {
        if (peer.label == null
                || peer.label.isBlank()) {
            return getString(
                    R.string.peer_unknown_device);
        }

        return peer.label;
    }

    private void showFailure(
            Exception exception) {
        String detail =
                exception == null
                        ? getString(
                                R.string.peer_unknown_error)
                        : exception.getClass()
                                .getSimpleName();

        runOnUiThread(
                () -> {
                    status.setText(
                            getString(
                                    R.string.peer_nearby_failed,
                                    detail));

                    pairingActive = false;
                    startButton.setEnabled(true);
                });
    }

    @Override
    protected void onDestroy() {
        if (client != null) {
            client.stopAdvertising();
            client.stopDiscovery();
            client.stopAllEndpoints();
        }

        super.onDestroy();
    }
}
