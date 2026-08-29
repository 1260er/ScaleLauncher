package de.pritcloud.scalelauncher;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class PeerMeasurementTransport {
    interface Listener {
        void onMessageReceived(
                PeerTrustStore.Peer peer,
                String payload);

        void onMessageSent(
                PeerTrustStore.Peer peer,
                String messageId);

        void onError(String message);
    }

    private static final UUID SERVICE_UUID_VALUE =
            UUID.fromString(
                    "62d58d1c-1d4e-4b7e-9d6d-8e6a9053c7a1");

    private static final UUID DATA_UUID =
            UUID.fromString(
                    "62d58d1d-1d4e-4b7e-9d6d-8e6a9053c7a1");

    private static final UUID CCCD_UUID =
            UUID.fromString(
                    "00002902-0000-1000-8000-00805f9b34fb");

    private static final ParcelUuid SERVICE_UUID =
            new ParcelUuid(
                    SERVICE_UUID_VALUE);

    private static final int FRAME_START = 1;
    private static final int FRAME_CONTINUE = 2;
    private static final int MAX_MESSAGE_BYTES = 8192;
    private static final long SEND_TIMEOUT_MS = 15_000L;
    private static final long REPLY_WAIT_TIMEOUT_MS = 5_000L;
    private static final long SESSION_IDLE_TIMEOUT_MS = 3_000L;

    private final Context context;
    private final Listener listener;
    private final Handler handler =
            new Handler(
                    Looper.getMainLooper());

    private final Map<String, ReceiveState> receiveStates =
            new HashMap<>();

    private final Set<String> notificationSubscribers =
            new HashSet<>();

    private final Map<String, BluetoothDevice> replyDevices =
            new HashMap<>();

    private final Map<String, Integer> replyMtus =
            new HashMap<>();

    private final Map<String, ReplySendState> replyStates =
            new HashMap<>();

    private BluetoothManager manager;
    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private BluetoothGattServer server;

    private BluetoothGatt sendGatt;
    private BluetoothGattCharacteristic sendCharacteristic;
    private String sendSessionPeerDeviceId;
    private PeerTrustStore.Peer sendPeer;
    private String sendMessageId;
    private String sendPayload;
    private byte[] sendBytes;
    private byte[] sendTargetFingerprint;
    private int sendOffset;
    private int sendMtu = 23;
    private boolean sendConnecting;
    private boolean sendLastChunkFinal;
    private boolean sendAwaitingReply;
    private String sendStage = "idle";

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
                            BluetoothGattCharacteristic.PROPERTY_WRITE
                                    | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                            BluetoothGattCharacteristic.PERMISSION_WRITE);

            BluetoothGattDescriptor cccd =
                    new BluetoothGattDescriptor(
                            CCCD_UUID,
                            BluetoothGattDescriptor.PERMISSION_READ
                                    | BluetoothGattDescriptor.PERMISSION_WRITE);

            data.addDescriptor(
                    cccd);

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

    boolean send(
            PeerTrustStore.Peer peer,
            String messageId,
            String payload) {
        if (peer == null
                || messageId == null
                || messageId.isBlank()
                || payload == null
                || payload.isBlank()
                || sendPeer != null) {
            return false;
        }

        if (!hasBlePermissions()
                || adapter == null
                || !adapter.isEnabled()
                || scanner == null) {
            reportError(
                    "BLE-Peer-Sendung nicht möglich");
            return false;
        }

        boolean reuseSession =
                canReuseSendSession(
                        peer);

        if (!reuseSession
                && sendGatt != null) {
            cleanupSendConnection();
            clearSendState();
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

            byte[] bytes =
                    encrypted.getBytes(
                            StandardCharsets.UTF_8);

            if (bytes.length == 0
                    || bytes.length > MAX_MESSAGE_BYTES) {
                reportError(
                        "BLE-Peer-Nachricht ist zu groß");
                return false;
            }

            sendPeer = peer;
            sendMessageId = messageId;
            sendPayload = payload;
            sendBytes = bytes;
            sendOffset = 0;
            sendConnecting = false;
            sendLastChunkFinal = false;
            sendAwaitingReply = false;

            if (reuseSession) {
                handler.removeCallbacks(
                        sessionIdleTimeoutTask);

                sendStage = "writing";

                EventLog.debug(
                        context,
                        context.getString(
                                R.string.log_peer_connection_reused,
                                peer.label));

                handler.postDelayed(
                        sendTimeoutTask,
                        SEND_TIMEOUT_MS);

                writeNextChunk(
                        sendGatt,
                        sendCharacteristic);

                return true;
            }

            sendMtu = 23;

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

            sendStage = "scanning";

            EventLog.debug(
                    context,
                    "Peer-Transport: Suche nach "
                            + peer.label);

            handler.postDelayed(
                    sendTimeoutTask,
                    SEND_TIMEOUT_MS);

            return true;
        } catch (RuntimeException exception) {
            failSend(
                    "BLE-Peer-Sendung: "
                            + exception.getClass().getSimpleName());
            return false;
        }
    }

    boolean sendReply(
            PeerTrustStore.Peer peer,
            String messageId,
            String payload) {
        if (peer == null
                || messageId == null
                || messageId.isBlank()
                || payload == null
                || payload.isBlank()
                || server == null) {
            return false;
        }

        BluetoothDevice device =
                replyDevices.get(
                        peer.deviceId);

        if (device == null
                || !notificationSubscribers.contains(
                        device.getAddress())
                || replyStates.containsKey(
                        device.getAddress())) {
            return false;
        }

        BluetoothGattService service =
                server.getService(
                        SERVICE_UUID_VALUE);

        BluetoothGattCharacteristic characteristic =
                service == null
                        ? null
                        : service.getCharacteristic(
                                DATA_UUID);

        if (characteristic == null) {
            return false;
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

            byte[] bytes =
                    encrypted.getBytes(
                            StandardCharsets.UTF_8);

            if (bytes.length == 0
                    || bytes.length > MAX_MESSAGE_BYTES) {
                return false;
            }

            ReplySendState state =
                    new ReplySendState(
                            peer,
                            messageId,
                            device,
                            characteristic,
                            bytes,
                            replyMtus.getOrDefault(
                                    device.getAddress(),
                                    23));

            replyStates.put(
                    device.getAddress(),
                    state);

            if (!writeNextReplyChunk(
                    state)) {
                replyStates.remove(
                        device.getAddress());
                return false;
            }

            return true;
        } catch (RuntimeException exception) {
            replyStates.remove(
                    device.getAddress());
            return false;
        }
    }

    void stop() {
        handler.removeCallbacks(
                sendTimeoutTask);

        handler.removeCallbacks(
                replyWaitTimeoutTask);

        handler.removeCallbacks(
                sessionIdleTimeoutTask);

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
        notificationSubscribers.clear();
        replyDevices.clear();
        replyMtus.clear();
        replyStates.clear();
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

                    EventLog.debug(
                            context,
                            "Peer-Transport: GATT-Dienst bereit");

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

                @Override
                public void onDescriptorWriteRequest(
                        BluetoothDevice device,
                        int requestId,
                        BluetoothGattDescriptor descriptor,
                        boolean preparedWrite,
                        boolean responseNeeded,
                        int offset,
                        byte[] value) {
                    int response =
                            BluetoothGatt.GATT_SUCCESS;

                    BluetoothGattCharacteristic characteristic =
                            descriptor == null
                                    ? null
                                    : descriptor.getCharacteristic();

                    if (device == null
                            || descriptor == null
                            || !CCCD_UUID.equals(
                                    descriptor.getUuid())
                            || characteristic == null
                            || !DATA_UUID.equals(
                                    characteristic.getUuid())
                            || preparedWrite
                            || offset != 0
                            || value == null) {
                        response =
                                BluetoothGatt.GATT_FAILURE;
                    } else if (Arrays.equals(
                            value,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                        notificationSubscribers.add(
                                device.getAddress());
                    } else if (Arrays.equals(
                            value,
                            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                        notificationSubscribers.remove(
                                device.getAddress());
                    } else {
                        response =
                                BluetoothGatt.GATT_FAILURE;
                    }

                    if (responseNeeded
                            && server != null
                            && device != null) {
                        server.sendResponse(
                                device,
                                requestId,
                                response,
                                offset,
                                null);
                    }
                }

                @Override
                public void onConnectionStateChange(
                        BluetoothDevice device,
                        int status,
                        int newState) {
                    if (device != null
                            && newState
                            == BluetoothProfile.STATE_DISCONNECTED) {
                        String address =
                                device.getAddress();

                        notificationSubscribers.remove(
                                address);

                        receiveStates.remove(
                                address);

                        replyMtus.remove(
                                address);

                        replyStates.remove(
                                address);

                        replyDevices.entrySet().removeIf(
                                entry -> entry.getValue() != null
                                        && address.equals(
                                                entry.getValue().getAddress()));
                    }
                }

                @Override
                public void onMtuChanged(
                        BluetoothDevice device,
                        int mtu) {
                    if (device != null
                            && mtu >= 23) {
                        replyMtus.put(
                                device.getAddress(),
                                mtu);
                    }
                }

                @Override
                public void onNotificationSent(
                        BluetoothDevice device,
                        int status) {
                    if (device == null) {
                        return;
                    }

                    String address =
                            device.getAddress();

                    ReplySendState state =
                            replyStates.get(
                                    address);

                    if (state == null) {
                        return;
                    }

                    if (status
                            != BluetoothGatt.GATT_SUCCESS) {
                        replyStates.remove(
                                address);
                        return;
                    }

                    if (state.offset
                            >= state.bytes.length) {
                        replyStates.remove(
                                address);
                        return;
                    }

                    if (!writeNextReplyChunk(
                            state)) {
                        replyStates.remove(
                                address);
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
                public void onStartSuccess(
                        AdvertiseSettings settingsInEffect) {
                    EventLog.debug(
                            context,
                            "Peer-Transport: Advertising aktiv");
                }

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
                    sendStage = "connecting";

                    EventLog.debug(
                            context,
                            "Peer-Transport: Ziel gefunden – "
                                    + sendPeer.label);

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
                        if (sendPeer != null) {
                            failSend(
                                    "BLE-Peer-Verbindung Status "
                                            + status);
                        } else if (gatt == sendGatt) {
                            cleanupSendConnection();
                            clearSendState();
                        }
                        return;
                    }

                    if (newState
                            == BluetoothProfile.STATE_CONNECTED) {
                        sendStage = "connected";

                        EventLog.debug(
                                context,
                                "Peer-Transport: Verbindung hergestellt – "
                                        + sendPeer.label);

                        if (!gatt.requestMtu(517)) {
                            sendStage = "discovering";
                            gatt.discoverServices();
                        }
                    } else if (newState
                            == BluetoothProfile.STATE_DISCONNECTED) {
                        if (sendPeer != null) {
                            failSend(
                                    "BLE-Peer-Verbindung getrennt");
                        } else if (gatt == sendGatt) {
                            cleanupSendConnection();
                            clearSendState();
                        }
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

                    sendStage = "discovering";

                    EventLog.debug(
                            context,
                            "Peer-Transport: MTU "
                                    + sendMtu
                                    + " – Dienste werden gesucht");

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

                    BluetoothGattDescriptor cccd =
                            characteristic.getDescriptor(
                                    CCCD_UUID);

                    if (cccd == null
                            || !gatt.setCharacteristicNotification(
                                    characteristic,
                                    true)) {
                        failSend(
                                "BLE-Peer-Rückkanal konnte nicht aktiviert werden");
                        return;
                    }

                    sendStage = "subscribing";

                    boolean queued;

                    if (Build.VERSION.SDK_INT >= 33) {
                        queued =
                                gatt.writeDescriptor(
                                        cccd,
                                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                        == BluetoothStatusCodes.SUCCESS;
                    } else {
                        cccd.setValue(
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);

                        queued =
                                gatt.writeDescriptor(
                                        cccd);
                    }

                    if (!queued) {
                        failSend(
                                "BLE-Peer-Rückkanal konnte nicht eingereiht werden");
                    }
                }

                @Override
                public void onDescriptorWrite(
                        BluetoothGatt gatt,
                        BluetoothGattDescriptor descriptor,
                        int status) {
                    if (descriptor == null
                            || !CCCD_UUID.equals(
                                    descriptor.getUuid())) {
                        return;
                    }

                    if (status
                            != BluetoothGatt.GATT_SUCCESS) {
                        failSend(
                                "BLE-Peer-Rückkanal Status "
                                        + status);
                        return;
                    }

                    BluetoothGattCharacteristic characteristic =
                            descriptor.getCharacteristic();

                    if (characteristic == null
                            || !DATA_UUID.equals(
                                    characteristic.getUuid())) {
                        failSend(
                                "BLE-Peer-Messwertkanal fehlt");
                        return;
                    }

                    sendCharacteristic =
                            characteristic;

                    sendSessionPeerDeviceId =
                            sendPeer == null
                                    ? null
                                    : sendPeer.deviceId;

                    sendStage = "writing";

                    EventLog.debug(
                            context,
                            "Peer-Transport: Datenkanal bereit – "
                                    + sendPeer.label);

                    writeNextChunk(
                            gatt,
                            characteristic);
                }

                @Override
                public void onCharacteristicChanged(
                        BluetoothGatt gatt,
                        BluetoothGattCharacteristic characteristic,
                        byte[] value) {
                    handleReplyNotification(
                            gatt,
                            characteristic,
                            value);
                }

                @SuppressWarnings("deprecation")
                @Override
                public void onCharacteristicChanged(
                        BluetoothGatt gatt,
                        BluetoothGattCharacteristic characteristic) {
                    handleReplyNotification(
                            gatt,
                            characteristic,
                            characteristic == null
                                    ? null
                                    : characteristic.getValue());
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

    private void handleReplyNotification(
            BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic,
            byte[] value) {
        if (gatt == null
                || gatt != sendGatt
                || !sendAwaitingReply
                || characteristic == null
                || !DATA_UUID.equals(
                        characteristic.getUuid())
                || value == null
                || value.length == 0) {
            return;
        }

        BluetoothDevice device =
                gatt.getDevice();

        if (!acceptFrame(
                device,
                value)) {
            return;
        }

        if (!receiveStates.containsKey(
                device.getAddress())) {
            finishReplyWait();
        }
    }

    private boolean writeNextReplyChunk(
            ReplySendState state) {
        if (state == null
                || server == null
                || state.offset >= state.bytes.length) {
            return false;
        }

        int maxValueLength =
                Math.min(
                        512,
                        Math.max(
                                20,
                                state.mtu - 3));

        boolean first =
                state.offset == 0;

        int headerLength =
                first ? 5 : 1;

        int dataLength =
                Math.min(
                        maxValueLength - headerLength,
                        state.bytes.length - state.offset);

        if (dataLength <= 0) {
            return false;
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
                    state.bytes.length;

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
                state.bytes,
                state.offset,
                frame,
                headerLength,
                dataLength);

        boolean queued;

        if (Build.VERSION.SDK_INT >= 33) {
            queued =
                    server.notifyCharacteristicChanged(
                            state.device,
                            state.characteristic,
                            false,
                            frame)
                            == BluetoothStatusCodes.SUCCESS;
        } else {
            state.characteristic.setValue(
                    frame);

            queued =
                    server.notifyCharacteristicChanged(
                            state.device,
                            state.characteristic,
                            false);
        }

        if (queued) {
            state.offset +=
                    dataLength;
        }

        return queued;
    }

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
                Math.min(
                        512,
                        Math.max(
                                20,
                                sendMtu - 3));

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

        String payload =
                PeerMeasurementCrypto.decryptPlaintext(
                        encrypted,
                        peer);

        if (payload == null
                || payload.isBlank()) {
            return false;
        }

        replyDevices.put(
                peer.deviceId,
                device);

        EventLog.debug(
                context,
                "Peer-Transport: Nachricht empfangen von "
                        + peer.label);

        handler.post(
                () -> listener.onMessageReceived(
                        peer,
                        payload));

        return true;
    }

    private void finishSend() {
        PeerTrustStore.Peer peer =
                sendPeer;

        String messageId =
                sendMessageId;

        handler.removeCallbacks(
                sendTimeoutTask);

        sendAwaitingReply = true;
        sendStage = "awaiting_reply";

        handler.postDelayed(
                replyWaitTimeoutTask,
                REPLY_WAIT_TIMEOUT_MS);

        if (peer != null
                && messageId != null) {
            EventLog.debug(
                    context,
                    "Peer-Transport: Nachricht gesendet an "
                            + peer.label);

            handler.post(
                    () -> listener.onMessageSent(
                            peer,
                            messageId));
        }
    }

    private void finishReplyWait() {
        handler.removeCallbacks(
                replyWaitTimeoutTask);

        clearActiveSendState();

        if (sendGatt != null
                && sendCharacteristic != null
                && sendSessionPeerDeviceId != null) {
            handler.removeCallbacks(
                    sessionIdleTimeoutTask);

            handler.postDelayed(
                    sessionIdleTimeoutTask,
                    SESSION_IDLE_TIMEOUT_MS);

            return;
        }

        cleanupSendConnection();
        clearSendState();
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

        handler.removeCallbacks(
                replyWaitTimeoutTask);

        handler.removeCallbacks(
                sessionIdleTimeoutTask);

        stopSendScan();

        BluetoothGatt gatt =
                sendGatt;

        sendGatt = null;
        sendCharacteristic = null;
        sendSessionPeerDeviceId = null;

        if (gatt != null) {
            try {
                gatt.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void clearActiveSendState() {
        sendPeer = null;
        sendMessageId = null;
        sendPayload = null;
        sendBytes = null;
        sendTargetFingerprint = null;
        sendOffset = 0;
        sendConnecting = false;
        sendLastChunkFinal = false;
        sendAwaitingReply = false;
        sendStage =
                sendGatt == null
                        ? "idle"
                        : "session_idle";
    }

    private void clearSendState() {
        clearActiveSendState();
        sendMtu = 23;
        sendCharacteristic = null;
        sendSessionPeerDeviceId = null;
        sendStage = "idle";
    }

    private boolean canReuseSendSession(
            PeerTrustStore.Peer peer) {
        return peer != null
                && sendPeer == null
                && sendGatt != null
                && sendCharacteristic != null
                && sendSessionPeerDeviceId != null
                && sendSessionPeerDeviceId.equals(
                        peer.deviceId);
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

    private final Runnable sessionIdleTimeoutTask =
            () -> {
                if (sendPeer == null
                        && sendGatt != null) {
                    cleanupSendConnection();
                    clearSendState();
                }
            };

    private final Runnable replyWaitTimeoutTask =
            () -> {
                if (sendAwaitingReply) {
                    finishReplyWait();
                }
            };

    private final Runnable sendTimeoutTask =
            () -> {
                if (sendPeer != null) {
                    failSend(
                            "BLE-Peer-Sendung Zeitlimit überschritten (Stufe: "
                                    + sendStage
                                    + ")");
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

    private static final class ReplySendState {
        final PeerTrustStore.Peer peer;
        final String messageId;
        final BluetoothDevice device;
        final BluetoothGattCharacteristic characteristic;
        final byte[] bytes;
        final int mtu;
        int offset;

        ReplySendState(
                PeerTrustStore.Peer peer,
                String messageId,
                BluetoothDevice device,
                BluetoothGattCharacteristic characteristic,
                byte[] bytes,
                int mtu) {
            this.peer = peer;
            this.messageId = messageId;
            this.device = device;
            this.characteristic = characteristic;
            this.bytes = bytes;
            this.mtu =
                    Math.max(
                            23,
                            mtu);
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
