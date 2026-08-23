package de.pritcloud.scalelauncher;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
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
import android.os.Build;
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
import java.util.List;
import java.util.UUID;

public final class PeerPairingActivity extends Activity {
    private static final int REQ_BLE_PERMISSIONS = 401;
    private static final long PAIRING_TIMEOUT_MS = 45_000L;

    private static final UUID SERVICE_UUID_VALUE =
            UUID.fromString(
                    "62d58d1a-1d4e-4b7e-9d6d-8e6a9053c7a1");

    private static final UUID HANDSHAKE_UUID =
            UUID.fromString(
                    "62d58d1b-1d4e-4b7e-9d6d-8e6a9053c7a1");

    private static final ParcelUuid SERVICE_UUID =
            new ParcelUuid(
                    SERVICE_UUID_VALUE);

    private final Handler handler =
            new Handler(
                    Looper.getMainLooper());

    private BluetoothManager manager;
    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private BluetoothGattServer gattServer;
    private BluetoothGatt clientGatt;

    private PeerEndpointInfo localEndpoint;
    private PeerPairingCrypto.Session cryptoSession;
    private byte[] localFingerprint;

    private TextView localInfo;
    private TextView trustedInfo;
    private TextView status;
    private Button startButton;

    private boolean pairingActive;
    private boolean clientConnecting;
    private boolean codeReady;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(
                R.layout.activity_peer_pairing);

        View contentRoot =
                findViewById(
                        android.R.id.content);

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

        manager =
                getSystemService(
                        BluetoothManager.class);

        adapter =
                manager == null
                        ? null
                        : manager.getAdapter();

        localEndpoint =
                PeerEndpointInfo.local(this);

        localFingerprint =
                fingerprint(
                        localEndpoint.deviceId);

        localInfo =
                findViewById(
                        R.id.peerLocalInfo);

        trustedInfo =
                findViewById(
                        R.id.peerTrustedInfo);

        status =
                findViewById(
                        R.id.peerPairingStatus);

        startButton =
                findViewById(
                        R.id.startPeerPairing);

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
                    missing.toArray(
                            new String[0]),
                    REQ_BLE_PERMISSIONS);
            return;
        }

        startPairing();
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

        if (requestCode
                != REQ_BLE_PERMISSIONS) {
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

        startPairing();
    }

    private void startPairing() {
        stopBle();

        if (adapter == null
                || manager == null
                || !adapter.isEnabled()) {
            fail(
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
            fail(
                    getString(
                            R.string.peer_ble_unavailable));
            return;
        }

        cryptoSession =
                PeerPairingCrypto.newSession(
                        localEndpoint.deviceId);

        pairingActive = true;
        clientConnecting = false;
        codeReady = false;

        startButton.setEnabled(false);

        status.setText(
                R.string.peer_pair_secure_searching);

        EventLog.info(
                this,
                getString(
                        R.string.peer_pair_secure_started));

        openGattServer();

        handler.postDelayed(
                timeoutTask,
                PAIRING_TIMEOUT_MS);
    }

    private void openGattServer() {
        try {
            gattServer =
                    manager.openGattServer(
                            this,
                            serverCallback);

            if (gattServer == null) {
                fail(
                        getString(
                                R.string.peer_pair_gatt_server_failed));
                return;
            }

            BluetoothGattService service =
                    new BluetoothGattService(
                            SERVICE_UUID_VALUE,
                            BluetoothGattService.SERVICE_TYPE_PRIMARY);

            BluetoothGattCharacteristic handshake =
                    new BluetoothGattCharacteristic(
                            HANDSHAKE_UUID,
                            BluetoothGattCharacteristic.PROPERTY_READ
                                    | BluetoothGattCharacteristic.PROPERTY_WRITE,
                            BluetoothGattCharacteristic.PERMISSION_READ
                                    | BluetoothGattCharacteristic.PERMISSION_WRITE);

            service.addCharacteristic(
                    handshake);

            if (!gattServer.addService(service)) {
                fail(
                        getString(
                                R.string.peer_pair_gatt_server_failed));
            }
        } catch (RuntimeException exception) {
            fail(
                    getString(
                            R.string.peer_pair_gatt_server_error,
                            exception.getClass()
                                    .getSimpleName()));
        }
    }

    private void startAdvertisingAndScanning() {
        if (!pairingActive) return;

        AdvertiseSettings settings =
                new AdvertiseSettings.Builder()
                        .setAdvertiseMode(
                                AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                        .setTxPowerLevel(
                                AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                        .setConnectable(true)
                        .setTimeout(0)
                        .build();

        AdvertiseData data =
                new AdvertiseData.Builder()
                        .addServiceUuid(
                                SERVICE_UUID)
                        .setIncludeDeviceName(false)
                        .build();

        AdvertiseData response =
                new AdvertiseData.Builder()
                        .addServiceData(
                                SERVICE_UUID,
                                localFingerprint)
                        .build();

        try {
            advertiser.startAdvertising(
                    settings,
                    data,
                    response,
                    advertiseCallback);

            startScanning();
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

    private final BluetoothGattServerCallback
            serverCallback =
            new BluetoothGattServerCallback() {
                @Override
                public void onServiceAdded(
                        int statusCode,
                        BluetoothGattService service) {
                    if (!SERVICE_UUID_VALUE.equals(
                            service.getUuid())) {
                        return;
                    }

                    if (statusCode
                            != BluetoothGatt.GATT_SUCCESS) {
                        fail(
                                getString(
                                        R.string.peer_pair_gatt_server_status,
                                        statusCode));
                        return;
                    }

                    EventLog.info(
                            PeerPairingActivity.this,
                            getString(
                                    R.string.peer_pair_gatt_server_ready));

                    runOnUiThread(
                            () -> startAdvertisingAndScanning());
                }

                @Override
                public void onCharacteristicReadRequest(
                        BluetoothDevice device,
                        int requestId,
                        int offset,
                        BluetoothGattCharacteristic characteristic) {
                    if (!HANDSHAKE_UUID.equals(
                            characteristic.getUuid())) {
                        gattServer.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                                offset,
                                null);
                        return;
                    }

                    byte[] hello =
                            cryptoSession.hello;

                    if (offset < 0
                            || offset > hello.length) {
                        gattServer.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_INVALID_OFFSET,
                                offset,
                                null);
                        return;
                    }

                    byte[] value =
                            Arrays.copyOfRange(
                                    hello,
                                    offset,
                                    hello.length);

                    gattServer.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            offset,
                            value);
                }

                @Override
                public void onCharacteristicWriteRequest(
                        BluetoothDevice device,
                        int requestId,
                        BluetoothGattCharacteristic characteristic,
                        boolean preparedWrite,
                        boolean responseNeeded,
                        int offset,
                        byte[] value) {
                    int response =
                            BluetoothGatt.GATT_SUCCESS;

                    if (!HANDSHAKE_UUID.equals(
                            characteristic.getUuid())
                            || preparedWrite
                            || offset != 0) {
                        response =
                                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                    } else {
                        PeerPairingCrypto.Remote remote =
                                PeerPairingCrypto.parseHello(
                                        value);

                        if (remote == null) {
                            response =
                                    BluetoothGatt.GATT_FAILURE;
                        } else {
                            handleRemoteHello(
                                    remote);
                        }
                    }

                    if (responseNeeded) {
                        gattServer.sendResponse(
                                device,
                                requestId,
                                response,
                                offset,
                                null);
                    }
                }
            };

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

    private final ScanCallback
            scanCallback =
            new ScanCallback() {
                @Override
                public void onScanResult(
                        int callbackType,
                        ScanResult result) {
                    handleScanResult(
                            result);
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

    private void handleScanResult(
            ScanResult result) {
        if (!pairingActive
                || codeReady
                || clientConnecting
                || result == null
                || result.getScanRecord() == null) {
            return;
        }

        byte[] remoteFingerprint =
                result.getScanRecord()
                        .getServiceData(
                                SERVICE_UUID);

        if (remoteFingerprint == null
                || remoteFingerprint.length == 0
                || Arrays.equals(
                        remoteFingerprint,
                        localFingerprint)) {
            return;
        }

        /*
         * Both phones advertise and scan.
         * Only one side becomes GATT client.
         */
        if (compareUnsigned(
                localFingerprint,
                remoteFingerprint) >= 0) {
            return;
        }

        clientConnecting = true;

        try {
            scanner.stopScan(
                    scanCallback);
        } catch (RuntimeException ignored) {
        }

        status.setText(
                R.string.peer_pair_connecting);

        EventLog.info(
                this,
                getString(
                        R.string.peer_pair_connecting));

        try {
            clientGatt =
                    result.getDevice()
                            .connectGatt(
                                    this,
                                    false,
                                    clientCallback,
                                    BluetoothDevice.TRANSPORT_LE);

            if (clientGatt == null) {
                fail(
                        getString(
                                R.string.peer_pair_connection_failed));
            }
        } catch (RuntimeException exception) {
            fail(
                    getString(
                            R.string.peer_pair_connection_error,
                            exception.getClass()
                                    .getSimpleName()));
        }
    }

    private final BluetoothGattCallback
            clientCallback =
            new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(
                        BluetoothGatt gatt,
                        int gattStatus,
                        int newState) {
                    if (gattStatus
                            != BluetoothGatt.GATT_SUCCESS) {
                        fail(
                                getString(
                                        R.string.peer_pair_connection_status,
                                        gattStatus));
                        return;
                    }

                    if (newState
                            == BluetoothProfile.STATE_CONNECTED) {
                        EventLog.info(
                                PeerPairingActivity.this,
                                getString(
                                        R.string.peer_pair_connected));

                        if (!gatt.requestMtu(96)) {
                            gatt.discoverServices();
                        }
                    } else if (newState
                            == BluetoothProfile.STATE_DISCONNECTED
                            && pairingActive
                            && !codeReady) {
                        fail(
                                getString(
                                        R.string.peer_pair_disconnected));
                    }
                }

                @Override
                public void onMtuChanged(
                        BluetoothGatt gatt,
                        int mtu,
                        int gattStatus) {
                    gatt.discoverServices();
                }

                @Override
                public void onServicesDiscovered(
                        BluetoothGatt gatt,
                        int gattStatus) {
                    if (gattStatus
                            != BluetoothGatt.GATT_SUCCESS) {
                        fail(
                                getString(
                                        R.string.peer_pair_service_failed,
                                        gattStatus));
                        return;
                    }

                    BluetoothGattService service =
                            gatt.getService(
                                    SERVICE_UUID_VALUE);

                    BluetoothGattCharacteristic handshake =
                            service == null
                                    ? null
                                    : service.getCharacteristic(
                                            HANDSHAKE_UUID);

                    if (handshake == null
                            || !gatt.readCharacteristic(
                                    handshake)) {
                        fail(
                                getString(
                                        R.string.peer_pair_service_missing));
                    }
                }

                @Override
                public void onCharacteristicRead(
                        BluetoothGatt gatt,
                        BluetoothGattCharacteristic characteristic,
                        byte[] value,
                        int gattStatus) {
                    handleClientRead(
                            gatt,
                            characteristic,
                            value,
                            gattStatus);
                }

                @Override
                public void onCharacteristicRead(
                        BluetoothGatt gatt,
                        BluetoothGattCharacteristic characteristic,
                        int gattStatus) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        return;
                    }

                    handleClientRead(
                            gatt,
                            characteristic,
                            characteristic.getValue(),
                            gattStatus);
                }

                @Override
                public void onCharacteristicWrite(
                        BluetoothGatt gatt,
                        BluetoothGattCharacteristic characteristic,
                        int gattStatus) {
                    if (gattStatus
                            != BluetoothGatt.GATT_SUCCESS) {
                        fail(
                                getString(
                                        R.string.peer_pair_write_failed,
                                        gattStatus));
                    }
                }
            };

    private void handleClientRead(
            BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic,
            byte[] value,
            int gattStatus) {
        if (!HANDSHAKE_UUID.equals(
                characteristic.getUuid())) {
            return;
        }

        if (gattStatus
                != BluetoothGatt.GATT_SUCCESS) {
            fail(
                    getString(
                            R.string.peer_pair_read_failed,
                            gattStatus));
            return;
        }

        PeerPairingCrypto.Remote remote =
                PeerPairingCrypto.parseHello(
                        value);

        if (remote == null) {
            fail(
                    getString(
                            R.string.peer_pair_invalid_handshake));
            return;
        }

        handleRemoteHello(
                remote);

        writeLocalHello(
                gatt,
                characteristic);
    }

    private void writeLocalHello(
            BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic) {
        boolean queued;

        if (Build.VERSION.SDK_INT >= 33) {
            queued =
                    gatt.writeCharacteristic(
                            characteristic,
                            cryptoSession.hello,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                            == BluetoothStatusCodes.SUCCESS;
        } else {
            characteristic.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            characteristic.setValue(
                    cryptoSession.hello);

            queued =
                    gatt.writeCharacteristic(
                            characteristic);
        }

        if (!queued) {
            fail(
                    getString(
                            R.string.peer_pair_write_queue_failed));
        }
    }

    private synchronized void handleRemoteHello(
            PeerPairingCrypto.Remote remote) {
        if (codeReady) return;

        if (PeerTrustStore.isTrusted(
                this,
                remote.deviceId)) {
            codeReady = true;

            handler.removeCallbacks(
                    timeoutTask);

            stopRadioOnly();

            runOnUiThread(
                    () -> {
                        status.setText(
                                R.string.peer_pair_already_trusted);
                        startButton.setEnabled(true);
                    });

            return;
        }

        try {
            PeerPairingCrypto.Result result =
                    PeerPairingCrypto.derive(
                            cryptoSession,
                            remote);

            codeReady = true;

            handler.removeCallbacks(
                    timeoutTask);

            stopRadioOnly();

            EventLog.info(
                    this,
                    getString(
                            R.string.peer_pair_code_log));

            runOnUiThread(
                    () -> {
                        status.setText(
                                getString(
                                        R.string.peer_pair_code_ready,
                                        result.securityCode));

                        startButton.setEnabled(true);
                    });
        } catch (RuntimeException exception) {
            fail(
                    getString(
                            R.string.peer_pair_crypto_failed,
                            exception.getClass()
                                    .getSimpleName()));
        }
    }

    private final Runnable timeoutTask =
            () -> {
                if (!pairingActive
                        || codeReady) {
                    return;
                }

                fail(
                        getString(
                                R.string.peer_pair_timeout));
            };

    private void fail(
            String message) {
        stopBle();

        runOnUiThread(
                () -> {
                    status.setText(message);
                    startButton.setEnabled(true);
                });

        EventLog.error(
                this,
                message);
    }

    private void stopRadioOnly() {
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
    }

    private void stopBle() {
        handler.removeCallbacks(
                timeoutTask);

        stopRadioOnly();

        if (clientGatt != null) {
            try {
                clientGatt.disconnect();
            } catch (RuntimeException ignored) {
            }

            clientGatt.close();
            clientGatt = null;
        }

        if (gattServer != null) {
            try {
                gattServer.clearServices();
            } catch (RuntimeException ignored) {
            }

            gattServer.close();
            gattServer = null;
        }

        pairingActive = false;
        clientConnecting = false;
    }

    private static int compareUnsigned(
            byte[] left,
            byte[] right) {
        int length =
                Math.min(
                        left.length,
                        right.length);

        for (int index = 0;
             index < length;
             index++) {
            int a =
                    left[index] & 0xff;

            int b =
                    right[index] & 0xff;

            if (a != b) {
                return Integer.compare(
                        a,
                        b);
            }
        }

        return Integer.compare(
                left.length,
                right.length);
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

    @Override
    protected void onDestroy() {
        stopBle();
        super.onDestroy();
    }
}
