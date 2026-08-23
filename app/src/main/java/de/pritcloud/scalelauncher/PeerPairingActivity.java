package de.pritcloud.scalelauncher;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PeerPairingActivity extends Activity {
    private static final int REQ_BLE_PERMISSIONS = 401;
    private static final long DISCOVERY_TIME_MS = 30_000L;

    /*
     * ScaleLauncher private BLE service UUID.
     * Used only to identify ScaleLauncher peers.
     */
    private static final ParcelUuid SERVICE_UUID =
            new ParcelUuid(
                    UUID.fromString(
                            "62d58d1a-1d4e-4b7e-9d6d-8e6a9053c7a1"));

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final Set<String> detectedPeers =
            new HashSet<>();

    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;

    private PeerEndpointInfo localEndpoint;
    private byte[] localFingerprint;

    private TextView localInfo;
    private TextView trustedInfo;
    private TextView status;
    private Button startButton;

    private boolean testActive;

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

        BluetoothManager manager =
                getSystemService(BluetoothManager.class);

        adapter =
                manager == null
                        ? null
                        : manager.getAdapter();

        localEndpoint = PeerEndpointInfo.local(this);
        localFingerprint =
                fingerprint(localEndpoint.deviceId);

        localInfo =
                findViewById(R.id.peerLocalInfo);

        trustedInfo =
                findViewById(R.id.peerTrustedInfo);

        status =
                findViewById(R.id.peerPairingStatus);

        startButton =
                findViewById(R.id.startPeerPairing);

        startButton.setOnClickListener(
                view -> ensurePermissionsAndStart());

        refreshSummary();
    }

    private void refreshSummary() {
        localInfo.setText(
                getString(
                        R.string.peer_local_device,
                        localEndpoint.label));

        int trusted =
                PeerTrustStore.count(this);

        trustedInfo.setText(
                getResources().getQuantityString(
                        R.plurals.peer_trusted_count,
                        trusted,
                        trusted));
    }

    private void ensurePermissionsAndStart() {
        List<String> missing =
                new ArrayList<>();

        addIfMissing(
                missing,
                Manifest.permission.BLUETOOTH_SCAN);

        addIfMissing(
                missing,
                Manifest.permission.BLUETOOTH_CONNECT);

        addIfMissing(
                missing,
                Manifest.permission.BLUETOOTH_ADVERTISE);

        if (!missing.isEmpty()) {
            requestPermissions(
                    missing.toArray(new String[0]),
                    REQ_BLE_PERMISSIONS);
            return;
        }

        startBleTest();
    }

    private void addIfMissing(
            List<String> missing,
            String permission) {
        if (checkSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(permission);
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

        if (requestCode != REQ_BLE_PERMISSIONS) {
            return;
        }

        if (grantResults.length == 0) {
            status.setText(
                    R.string.peer_permission_missing);
            return;
        }

        for (int result : grantResults) {
            if (result
                    != PackageManager.PERMISSION_GRANTED) {
                status.setText(
                        R.string.peer_permission_missing);
                return;
            }
        }

        startBleTest();
    }

    private void startBleTest() {
        if (testActive) return;

        if (adapter == null
                || !adapter.isEnabled()) {
            status.setText(
                    R.string.peer_bluetooth_off);

            EventLog.warning(
                    this,
                    getString(
                            R.string.peer_bluetooth_off));
            return;
        }

        advertiser =
                adapter.getBluetoothLeAdvertiser();

        scanner =
                adapter.getBluetoothLeScanner();

        if (advertiser == null
                || scanner == null) {
            status.setText(
                    R.string.peer_ble_unavailable);

            EventLog.error(
                    this,
                    getString(
                            R.string.peer_ble_unavailable));
            return;
        }

        detectedPeers.clear();
        testActive = true;
        startButton.setEnabled(false);

        status.setText(
                R.string.peer_searching);

        EventLog.info(
                this,
                getString(
                        R.string.peer_ble_test_started));

        startAdvertising();
        startScanning();

        handler.removeCallbacks(
                finishTask);

        handler.postDelayed(
                finishTask,
                DISCOVERY_TIME_MS);
    }

    private void startAdvertising() {
        AdvertiseSettings settings =
                new AdvertiseSettings.Builder()
                        .setAdvertiseMode(
                                AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                        .setTxPowerLevel(
                                AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                        .setConnectable(true)
                        .setTimeout(0)
                        .build();

        AdvertiseData advertiseData =
                new AdvertiseData.Builder()
                        .addServiceUuid(SERVICE_UUID)
                        .setIncludeDeviceName(false)
                        .setIncludeTxPowerLevel(false)
                        .build();

        /*
         * The short random installation fingerprint is placed in
         * the scan response. It is not a hardware identifier.
         */
        AdvertiseData scanResponse =
                new AdvertiseData.Builder()
                        .addServiceData(
                                SERVICE_UUID,
                                localFingerprint)
                        .build();

        try {
            advertiser.startAdvertising(
                    settings,
                    advertiseData,
                    scanResponse,
                    advertiseCallback);
        } catch (RuntimeException exception) {
            fail(
                    getString(
                            R.string.peer_ble_advertising_failed,
                            exception.getClass()
                                    .getSimpleName()));
        }
    }

    private void startScanning() {
        ScanFilter filter =
                new ScanFilter.Builder()
                        .setServiceUuid(
                                SERVICE_UUID)
                        .build();

        /*
         * Pairing is explicitly started by the user and lasts only
         * 30 seconds, therefore BALANCED is sufficient here.
         *
         * The later automatic background mode will use LOW_POWER.
         */
        ScanSettings settings =
                new ScanSettings.Builder()
                        .setScanMode(
                                ScanSettings.SCAN_MODE_BALANCED)
                        .build();

        try {
            scanner.startScan(
                    List.of(filter),
                    settings,
                    scanCallback);
        } catch (RuntimeException exception) {
            fail(
                    getString(
                            R.string.peer_ble_scan_failed,
                            exception.getClass()
                                    .getSimpleName()));
        }
    }

    private final AdvertiseCallback
            advertiseCallback =
            new AdvertiseCallback() {
                @Override
                public void onStartSuccess(
                        AdvertiseSettings settingsInEffect) {
                    EventLog.info(
                            PeerPairingActivity.this,
                            getString(
                                    R.string.peer_ble_advertising_active));
                }

                @Override
                public void onStartFailure(
                        int errorCode) {
                    fail(
                            getString(
                                    R.string.peer_ble_advertising_code,
                                    errorCode));
                }
            };

    private final ScanCallback scanCallback =
            new ScanCallback() {
                @Override
                public void onScanResult(
                        int callbackType,
                        ScanResult result) {
                    handleResult(result);
                }

                @Override
                public void onBatchScanResults(
                        List<ScanResult> results) {
                    for (ScanResult result : results) {
                        handleResult(result);
                    }
                }

                @Override
                public void onScanFailed(
                        int errorCode) {
                    fail(
                            getString(
                                    R.string.peer_ble_scan_code,
                                    errorCode));
                }
            };

    private void handleResult(
            ScanResult result) {
        if (!testActive
                || result == null
                || result.getScanRecord() == null) {
            return;
        }

        byte[] remoteFingerprint =
                result.getScanRecord()
                        .getServiceData(
                                SERVICE_UUID);

        if (remoteFingerprint != null
                && Arrays.equals(
                        remoteFingerprint,
                        localFingerprint)) {
            return;
        }

        String key;

        if (remoteFingerprint != null
                && remoteFingerprint.length > 0) {
            key = hex(remoteFingerprint);
        } else {
            key = "endpoint";
        }

        if (!detectedPeers.add(key)) {
            return;
        }

        String shortId =
                key.length() > 8
                        ? key.substring(0, 8)
                        : key;

        status.setText(
                getString(
                        R.string.peer_ble_found,
                        shortId));

        EventLog.info(
                this,
                getString(
                        R.string.peer_ble_found_log,
                        shortId));
    }

    private final Runnable finishTask =
            this::finishBleTest;

    private void finishBleTest() {
        if (!testActive) return;

        stopBle();

        status.setText(
                getResources().getQuantityString(
                        R.plurals.peer_ble_test_complete,
                        detectedPeers.size(),
                        detectedPeers.size()));

        EventLog.info(
                this,
                getResources().getQuantityString(
                        R.plurals.peer_ble_test_complete,
                        detectedPeers.size(),
                        detectedPeers.size()));

        startButton.setEnabled(true);
    }

    private void fail(
            String message) {
        stopBle();

        status.setText(message);

        EventLog.error(
                this,
                message);

        startButton.setEnabled(true);
    }

    private void stopBle() {
        handler.removeCallbacks(
                finishTask);

        if (scanner != null) {
            try {
                scanner.stopScan(
                        scanCallback);
            } catch (RuntimeException ignored) {
            }
        }

        if (advertiser != null) {
            try {
                advertiser.stopAdvertising(
                        advertiseCallback);
            } catch (RuntimeException ignored) {
            }
        }

        testActive = false;
    }

    private static byte[] fingerprint(
            String deviceId) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256");

            byte[] full =
                    digest.digest(
                            deviceId.getBytes(
                                    StandardCharsets.UTF_8));

            return Arrays.copyOf(
                    full,
                    6);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not create BLE peer fingerprint",
                    exception);
        }
    }

    private static String hex(
            byte[] data) {
        StringBuilder result =
                new StringBuilder();

        for (byte value : data) {
            result.append(
                    String.format(
                            java.util.Locale.ROOT,
                            "%02X",
                            value & 0xff));
        }

        return result.toString();
    }

    @Override
    protected void onDestroy() {
        stopBle();
        super.onDestroy();
    }
}
