package de.pritcloud.scalelauncher;

import android.Manifest;
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
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class PeerMeasurementTransport {
    interface Listener {
        void onMeasurementReceived(
                PeerTrustStore.Peer peer,
                PeerMeasurementPayload payload);

        void onMeasurementSent(
                PeerTrustStore.Peer peer,
                PeerMeasurementPayload payload);

        void onError(String message);
    }

    private static final UUID SERVICE_UUID_VALUE =
            UUID.fromString(
                    "62d58d1c-1d4e-4b7e-9d6d-8e6a9053c7a1");

    private static final UUID DATA_UUID =
            UUID.fromString(
                    "62d58d1d-1d4e-4b7e-9d6d-8e6a9053c7a1");

    private static final ParcelUuid SERVICE_UUID =
            new ParcelUuid(
                    SERVICE_UUID_VALUE);

    private static final int FRAME_START = 1;
    private static final int FRAME_CONTINUE = 2;
    private static final int MAX_MESSAGE_BYTES = 8192;
    private static final long SEND_TIMEOUT_MS = 20_000L;

    private final Context context;
    private final Listener listener;
    private final Handler handler =
            new Handler(
                    Looper.getMainLooper());

    private final Map<String, ReceiveState> receiveStates =
            new HashMap<>();

    private BluetoothManager manager;
    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private BluetoothGattServer server;

    private BluetoothGatt sendGatt;
    private PeerTrustStore.Peer sendPeer;
    private PeerMeasurementPayload sendPayload;
    private byte[] sendBytes;
    private byte[] sendTargetFingerprint;
    private int sendOffset;
    private int sendMtu = 23;
    private boolean sendConnecting;
    private boolean sendLastChunkFinal;

    PeerMeasurementTransport(
            Context context,
            Listener listener) {
        this.context =
                context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        if (!hasBlePermissions()) {
            reportError(
                    "Bluetooth-Berechtigungen für Peer-Transport fehlen");
            return;
        }

        manager =
                context.getSystemService(
                        BluetoothManager.class);

        adapter =
                manager == null
                        ? null
                        : manager.getAdapter();

        if (adapter == null
                || !adapter.isEnabled()) {
            reportError(
                    "Bluetooth für Peer-Transport nicht verfügbar");
            return;
        }

        advertiser =
                adapter.getBluetoothLeAdvertiser();

        scanner =
                adapter.getBluetoothLeScanner();

        if (advertiser == null
                || scanner == null) {
            reportError(
                    "BLE für Peer-Transport nicht verfügbar");
            return;
        }

        try {
            server =
                    manager.openGattServer(
                            context,
                            serverCallback);

            if (server == null) {
                reportError(
                        "BLE-Peer-GATT-Server konnte nicht gestartet werden");
                return;
            }

            BluetoothGattService service =
                    new BluetoothGattService(
                            SERVICE_UUID_VALUE,
                            BluetoothGattService.SERVICE_TYPE_PRIMARY);

            BluetoothGattCharacteristic data =
                    new BluetoothGattCharacteristic(
                            DATA_UUID,
                            BluetoothGattCharacteristic.PROPERTY_WRITE,
                            BluetoothGattCharacteristic.PERMISSION_WRITE);

            service.addCharacteristic(
                    data);

            if (!server.addService(service)) {
                reportError(
                        "BLE-Peer-Dienst konnte nicht registriert werden");
            }
        } catch (RuntimeException exception) {
            reportError(
                    "BLE-Peer-Transport: "
                            + exception.getClass().getSimpleName());
        }
    }

    void send(
            PeerTrustStore.Peer peer,
            PeerMeasurementPayload payload) {
        if (peer == null
                || payload == null
                || sendPeer != null) {
            return;
        }

        if (!hasBlePermissions()
                || adapter == null
                || !adapter.isEnabled()
                || scanner == null) {
            reportError(
                    "BLE-Peer-Sendung nicht möglich");
            return;
        }

        try {
            String localDeviceId =
                    PeerTrustStore.localDeviceId(
                            context);

            String encrypted =
                    PeerMeasurementCrypto.encrypt(
                            localDeviceId,
                            peer.sharedSecret,
                            payload);

            sendBytes =
                    encrypted.getBytes(
                            StandardCharsets.UTF_8);

            if (sendBytes.length == 0
                    || sendBytes.length > MAX_MESSAGE_BYTES) {
                reportError(
                        "BLE-Peer-Messung ist zu groß");
                clearSendState();
                return;
            }

            sendPeer = peer;
            sendPayload = payload;
            sendOffset = 0;
            sendMtu = 23;
            sendConnecting = false;
            sendLastChunkFinal = false;
            sendTargetFingerprint =
                    fingerprint(
                            peer.deviceId);

            ScanFilter filter =
                    new ScanFilter.Builder()
                            .setServiceUuid(
                                    SERVICE_UUID)
                            .build();

            ScanSettings settings =
                    new ScanSettings.Builder()
                            .setScanMode(
                                    ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build();

            scanner.startScan(
                    List.of(filter),
                    settings,
                    scanCallback);

            handler.postDelayed(
                    sendTimeoutTask,
                    SEND_TIMEOUT_MS);
        } catch (RuntimeException exception) {
            failSend(
                    "BLE-Peer-Sendung: "
                            + exception.getClass().getSimpleName());
        }
    }

    void stop() {
        handler.removeCallbacks(
                sendTimeoutTask);

        stopSendScan();

        if (advertiser != null) {
            try {
                advertiser.stopAdvertising(
                        advertiseCallback);
            } catch (RuntimeException ignored) {
            }
        }

        if (sendGatt != null) {
            try {
                sendGatt.close();
            } catch (RuntimeException ignored) {
            }
            sendGatt = null;
        }

        if (server != null) {
            try {
                server.clearServices();
            } catch (RuntimeException ignored) {
            }

            try {
                server.close();
            } catch (RuntimeException ignored) {
            }

            server = null;
        }

        receiveStates.clear();
        clearSendState();
    }

    private final BluetoothGattServerCallback serverCallback =
            new BluetoothGattServerCallback() {
                @Override
                public void onServiceAdded(
                        int status,
                        BluetoothGattService service) {
                    if (!SERVICE_UUID_VALUE.equals(
                            service.getUuid())) {
                        return;
                    }

                    if (status
                            != BluetoothGatt.GATT_SUCCESS) {
                        reportError(
                                "BLE-Peer-Dienst Status "
                                        + status);
                        return;
                    }

                    startAdvertising();
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

                    if (!DATA_UUID.equals(
                            characteristic.getUuid())
                            || preparedWrite
                            || offset != 0
                            || !acceptFrame(
                                    device,
                                    value)) {
                        response =
                                BluetoothGatt.GATT_FAILURE;
                    }

                    if (responseNeeded
                            && server != null) {
                        server.sendResponse(
                                device,
                                requestId,
                                response,
                                offset,
                                null);
                    }
                }
            };

    private void startAdvertising() {
        if (advertiser == null) return;

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
                                fingerprint(
                                        PeerTrustStore.localDeviceId(
                                                context)))
                        .build();

        try {
            advertiser.startAdvertising(
                    settings,
                    data,
                    response,
                    advertiseCallback);
        } catch (RuntimeException exception) {
            reportError(
                    "BLE-Peer-Advertising: "
                            + exception.getClass().getSimpleName());
        }
    }

    private final AdvertiseCallback advertiseCallback =
            new AdvertiseCallback() {
                @Override
                public void onStartFailure(
                        int errorCode) {
                    reportError(
                            "BLE-Peer-Advertising Fehler "
                                    + errorCode);
                }
            };

    private final ScanCallback scanCallback =
            new ScanCallback() {
                @Override
                public void onScanResult(
                        int callbackType,
                        ScanResult result) {
                    if (sendPeer == null
                            || sendConnecting
                            || result == null
                            || result.getScanRecord() == null) {
                        return;
                    }

                    byte[] remoteFingerprint =
                            result.getScanRecord()
                                    .getServiceData(
                                            SERVICE_UUID);

                    if (remoteFingerprint == null
                            || !Arrays.equals(
                                    remoteFingerprint,
                                    sendTargetFingerprint)) {
                        return;
                    }

                    sendConnecting = true;
                    stopSendScan();

                    try {
                        sendGatt =
                                result.getDevice()
                                        .connectGatt(
                                                context,
                                                false,
                                                sendCallback,
                                                BluetoothDevice.TRANSPORT_LE);

                        if (sendGatt == null) {
                            failSend(
                                    "BLE-Peer-Verbindung konnte nicht gestartet werden");
                        }
                    } catch (RuntimeException exception) {
                        failSend(
                                "BLE-Peer-Verbindung: "
                                        + exception.getClass().getSimpleName());
                    }
                }

                @Override
                public void onScanFailed(
                        int errorCode) {
                    failSend(
                            "BLE-Peer-Suche Fehler "
                                    + errorCode);
                }
            };

    private final BluetoothGattCallback sendCallback =
            new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(
                        BluetoothGatt gatt,
                        int status,
                        int newState) {
                    if (status
                            != BluetoothGatt.GATT_SUCCESS) {
                        failSend(
                                "BLE-Peer-Verbindung Status "
                                        + status);
                        return;
                    }

                    if (newState
                            == BluetoothProfile.STATE_CONNECTED) {
                        if (!gatt.requestMtu(517)) {
                            gatt.discoverServices();
                        }
                    } else if (newState
                            == BluetoothProfile.STATE_DISCONNECTED
                            && sendPeer != null) {
                        failSend(
                                "BLE-Peer-Verbindung getrennt");
                    }
                }

                @Override
                public void onMtuChanged(
                        BluetoothGatt gatt,
                        int mtu,
                        int status) {
                    if (status
                            == BluetoothGatt.GATT_SUCCESS) {
                        sendMtu =
                                Math.max(
                                        23,
                                        mtu);
                    }

                    gatt.discoverServices();
                }

                @Override
                public void onServicesDiscovered(
                        BluetoothGatt gatt,
                        int status) {
                    if (status
                            != BluetoothGatt.GATT_SUCCESS) {
                        failSend(
                                "BLE-Peer-Dienste Status "
                                        + status);
                        return;
                    }

                    BluetoothGattService service =
                            gatt.getService(
                                    SERVICE_UUID_VALUE);

                    BluetoothGattCharacteristic characteristic =
                            service == null
                                    ? null
                                    : service.getCharacteristic(
                                            DATA_UUID);

                    if (characteristic == null) {
                        failSend(
                                "BLE-Peer-Messwertkanal fehlt");
                        return;
                    }

                    writeNextChunk(
                            gatt,
                            characteristic);
                }

                @Override
                public void onCharacteristicWrite(
                        BluetoothGatt gatt,
                        BluetoothGattCharacteristic characteristic,
                        int status) {
                    if (status
                            != BluetoothGatt.GATT_SUCCESS) {
                        failSend(
                                "BLE-Peer-Schreiben Status "
                                        + status);
                        return;
                    }

                    if (sendLastChunkFinal) {
                        finishSend();
                        return;
                    }

                    writeNextChunk(
                            gatt,
                            characteristic);
                }
            };

    private void writeNextChunk(
            BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic) {
        if (sendBytes == null
                || sendOffset >= sendBytes.length) {
            failSend(
                    "BLE-Peer-Sendedaten fehlen");
            return;
        }

        int maxValueLength =
                Math.max(
                        20,
                        sendMtu - 3);

        boolean first =
                sendOffset == 0;

        int headerLength =
                first ? 5 : 1;

        int dataLength =
                Math.min(
                        maxValueLength - headerLength,
                        sendBytes.length - sendOffset);

        if (dataLength <= 0) {
            failSend(
                    "BLE-Peer-MTU zu klein");
            return;
        }

        byte[] frame =
                new byte[
                        headerLength
                                + dataLength];

        frame[0] =
                (byte) (first
                        ? FRAME_START
                        : FRAME_CONTINUE);

        if (first) {
            int total =
                    sendBytes.length;

            frame[1] =
                    (byte) (total >>> 24);
            frame[2] =
                    (byte) (total >>> 16);
            frame[3] =
                    (byte) (total >>> 8);
            frame[4] =
                    (byte) total;
        }

        System.arraycopy(
                sendBytes,
                sendOffset,
                frame,
                headerLength,
                dataLength);

        boolean queued;

        if (Build.VERSION.SDK_INT >= 33) {
            queued =
                    gatt.writeCharacteristic(
                            characteristic,
                            frame,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                            == BluetoothStatusCodes.SUCCESS;
        } else {
            characteristic.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

            characteristic.setValue(
                    frame);

            queued =
                    gatt.writeCharacteristic(
                            characteristic);
        }

        if (!queued) {
            failSend(
                    "BLE-Peer-Schreiben konnte nicht eingereiht werden");
            return;
        }

        sendOffset +=
                dataLength;

        sendLastChunkFinal =
                sendOffset
                        >= sendBytes.length;
    }

    private boolean acceptFrame(
            BluetoothDevice device,
            byte[] frame) {
        if (device == null
                || frame == null
                || frame.length < 2) {
            return false;
        }

        String address =
                device.getAddress();

        int type =
                frame[0] & 0xff;

        ReceiveState state;

        if (type == FRAME_START) {
            if (frame.length < 6) {
                return false;
            }

            int total =
                    ((frame[1] & 0xff) << 24)
                            | ((frame[2] & 0xff) << 16)
                            | ((frame[3] & 0xff) << 8)
                            | (frame[4] & 0xff);

            if (total <= 0
                    || total > MAX_MESSAGE_BYTES) {
                receiveStates.remove(
                        address);
                return false;
            }

            state =
                    new ReceiveState(
                            total);

            receiveStates.put(
                    address,
                    state);

            state.buffer.write(
                    frame,
                    5,
                    frame.length - 5);
        } else if (type
                == FRAME_CONTINUE) {
            state =
                    receiveStates.get(
                            address);

            if (state == null) {
                return false;
            }

            state.buffer.write(
                    frame,
                    1,
                    frame.length - 1);
        } else {
            return false;
        }

        if (state.buffer.size()
                > state.expectedLength) {
            receiveStates.remove(
                    address);
            return false;
        }

        if (state.buffer.size()
                < state.expectedLength) {
            return true;
        }

        receiveStates.remove(
                address);

        String encrypted =
                new String(
                        state.buffer.toByteArray(),
                        StandardCharsets.UTF_8);

        String senderDeviceId =
                PeerMeasurementCrypto.senderDeviceId(
                        encrypted);

        if (senderDeviceId == null) {
            return false;
        }

        PeerTrustStore.Peer peer =
                PeerTrustStore.find(
                        context,
                        senderDeviceId);

        if (peer == null) {
            return false;
        }

        PeerMeasurementPayload payload =
                PeerMeasurementCrypto.decrypt(
                        encrypted,
                        peer);

        if (payload == null) {
            return false;
        }

        handler.post(
                () -> listener.onMeasurementReceived(
                        peer,
                        payload));

        return true;
    }

    private void finishSend() {
        PeerTrustStore.Peer peer =
                sendPeer;

        PeerMeasurementPayload payload =
                sendPayload;

        cleanupSendConnection();
        clearSendState();

        if (peer != null
                && payload != null) {
            handler.post(
                    () -> listener.onMeasurementSent(
                            peer,
                            payload));
        }
    }

    private void failSend(
            String message) {
        cleanupSendConnection();
        clearSendState();
        reportError(
                message);
    }

    private void cleanupSendConnection() {
        handler.removeCallbacks(
                sendTimeoutTask);

        stopSendScan();

        BluetoothGatt gatt =
                sendGatt;

        sendGatt = null;

        if (gatt != null) {
            try {
                gatt.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void clearSendState() {
        sendPeer = null;
        sendPayload = null;
        sendBytes = null;
        sendTargetFingerprint = null;
        sendOffset = 0;
        sendMtu = 23;
        sendConnecting = false;
        sendLastChunkFinal = false;
    }

    private void stopSendScan() {
        if (scanner == null) return;

        try {
            scanner.stopScan(
                    scanCallback);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean hasBlePermissions() {
        return context.checkSelfPermission(
                        Manifest.permission.BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(
                        Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(
                        Manifest.permission.BLUETOOTH_ADVERTISE)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void reportError(
            String message) {
        handler.post(
                () -> listener.onError(
                        message));
    }

    private final Runnable sendTimeoutTask =
            () -> {
                if (sendPeer != null) {
                    failSend(
                            "BLE-Peer-Sendung Zeitlimit überschritten");
                }
            };

    private static byte[] fingerprint(
            String deviceId) {
        try {
            byte[] digest =
                    MessageDigest.getInstance(
                                    "SHA-256")
                            .digest(
                                    deviceId.getBytes(
                                            StandardCharsets.UTF_8));

            return Arrays.copyOf(
                    digest,
                    8);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not create peer fingerprint",
                    exception);
        }
    }

    private static final class ReceiveState {
        final int expectedLength;
        final ByteArrayOutputStream buffer =
                new ByteArrayOutputStream();

        ReceiveState(
                int expectedLength) {
            this.expectedLength =
                    expectedLength;
        }
    }
}
