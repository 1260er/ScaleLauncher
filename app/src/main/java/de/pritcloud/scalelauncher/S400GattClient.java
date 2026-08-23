package de.pritcloud.scalelauncher;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Android GATT client for Xiaomi Body Composition Scale S400.
 *
 * Implements the Mi Home v2 login and encrypted CMTP measurement channel.
 * Protocol behavior was independently implemented from the Apache-2.0
 * xiaomi-s400-live project.
 *
 * This class contains no openScale or Health Connect logic.
 */
public final class S400GattClient {
    private static final long CONNECT_TIMEOUT_MS = 30_000L;
    private static final long AUTH_TIMEOUT_MS = 8_000L;
    private static final long DEVICE_SETTLE_MS = 300L;
    private static final long PARCEL_FRAME_DELAY_MS = 50L;

    private static final List<UUID> NOTIFY_CHARACTERISTICS = Arrays.asList(
            S400GattProtocol.UPNP,
            S400GattProtocol.AVDTP,
            S400GattProtocol.AVCTP,
            S400GattProtocol.VEND1A,
            S400GattProtocol.CMTP,
            S400GattProtocol.VEND1C);

    public enum State {
        DISCONNECTED,
        CONNECTING,
        DISCOVERING,
        SUBSCRIBING,
        AUTHENTICATING,
        READY
    }

    public interface Listener {
        void onStateChanged(State state);
        void onAuthenticated();
        void onMeasurement(S400GattMeasurement measurement);
        void onDisconnected(int status);
        void onError(String message);
    }

    private enum AuthStep {
        IDLE,
        WAIT_KEY_READY,
        WAIT_KEY_OK,
        WAIT_DEVICE_RANDOM_HEADER,
        RECEIVE_DEVICE_RANDOM,
        WAIT_REMOTE_INFO_HEADER,
        RECEIVE_REMOTE_INFO,
        WAIT_INFO_READY,
        WAIT_INFO_OK,
        WAIT_LOGIN_RESULT,
        READY
    }

    private static final class WriteRequest {
        final UUID characteristicUuid;
        final byte[] value;
        final Runnable completed;
        final long delayAfterMs;

        WriteRequest(UUID characteristicUuid,
                     byte[] value,
                     Runnable completed,
                     long delayAfterMs) {
            this.characteristicUuid = characteristicUuid;
            this.value = Arrays.copyOf(value, value.length);
            this.completed = completed;
            this.delayAfterMs = delayAfterMs;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SecureRandom secureRandom = new SecureRandom();
    private final ArrayDeque<WriteRequest> writeQueue = new ArrayDeque<>();

    private BluetoothGatt gatt;
    private State state = State.DISCONNECTED;
    private AuthStep authStep = AuthStep.IDLE;

    private byte[] token;
    private byte[] appRandom;
    private byte[] deviceRandom;
    private byte[] remoteInfo;
    private byte[] ourInfo;
    private S400GattCrypto.SessionKeys sessionKeys;

    private int subscribeIndex;

    private WriteRequest currentWrite;
    private boolean writeInProgress;

    private int receiveExpectedFrames;
    private int receiveFrames;
    private ByteArrayOutputStream receiveBuffer;

    private int cmtpExpectedFrames;
    private ByteArrayOutputStream cmtpBuffer = new ByteArrayOutputStream();

    private boolean intentionalDisconnect;

    private final Runnable connectTimeoutRunnable = () -> {
        if (state != State.READY && state != State.DISCONNECTED) {
            fail("GATT-Verbindung oder Anmeldung hat das Zeitlimit überschritten");
        }
    };

    private final Runnable authTimeoutRunnable = () -> {
        if (state == State.AUTHENTICATING && authStep != AuthStep.READY) {
            fail("Mi-Home-v2-Anmeldung hat das Zeitlimit überschritten");
        }
    };

    public S400GattClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public State getState() {
        return state;
    }

    public boolean isReady() {
        return state == State.READY
                && authStep == AuthStep.READY
                && sessionKeys != null;
    }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device, String loginTokenHex) {
        connect(device, loginTokenHex, false);
    }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device,
                        String loginTokenHex,
                        boolean autoConnect) {
        handler.post(() ->
                connectInternal(device, loginTokenHex, autoConnect));
    }

    @SuppressLint("MissingPermission")
    private void connectInternal(BluetoothDevice device,
                                 String loginTokenHex,
                                 boolean autoConnect) {
        if (device == null) {
            fail("Kein Bluetooth-Gerät für GATT angegeben");
            return;
        }
        if (!S400GattProtocol.isValidLoginToken(loginTokenHex)) {
            fail("Ungültiger S400 Login-Token");
            return;
        }
        if (state != State.DISCONNECTED || gatt != null) {
            fail("GATT-Verbindung ist bereits aktiv");
            return;
        }

        resetProtocolState();
        token = S400GattProtocol.hex(loginTokenHex);
        intentionalDisconnect = false;
        setState(State.CONNECTING);

        try {
            gatt = device.connectGatt(
                    context,
                    autoConnect,
                    bluetoothGattCallback,
                    BluetoothDevice.TRANSPORT_LE);

            if (gatt == null) {
                fail("Android konnte keine GATT-Verbindung anlegen");
                return;
            }

            handler.removeCallbacks(connectTimeoutRunnable);
            if (!autoConnect) {
                handler.postDelayed(
                        connectTimeoutRunnable,
                        CONNECT_TIMEOUT_MS);
            }
        } catch (RuntimeException exception) {
            fail("GATT-Verbindung fehlgeschlagen: "
                    + exception.getClass().getSimpleName());
        }
    }

    public void disconnect() {
        handler.post(() -> {
            intentionalDisconnect = true;
            closeGatt(true);
        });
    }

    @SuppressLint("MissingPermission")
    private final BluetoothGattCallback bluetoothGattCallback =
            new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt callbackGatt,
                                            int status,
                                            int newState) {
            handler.post(() ->
                    handleConnectionState(callbackGatt, status, newState));
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt callbackGatt,
                                         int status) {
            handler.post(() ->
                    handleServicesDiscovered(callbackGatt, status));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt callbackGatt,
                                      BluetoothGattDescriptor descriptor,
                                      int status) {
            handler.post(() ->
                    handleDescriptorWrite(callbackGatt, descriptor, status));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt callbackGatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            handler.post(() ->
                    handleCharacteristicWrite(
                            callbackGatt,
                            characteristic,
                            status));
        }

        @Override
        public void onCharacteristicChanged(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            byte[] safe = value == null
                    ? new byte[0]
                    : Arrays.copyOf(value, value.length);
            handler.post(() ->
                    handleCharacteristicChanged(characteristic.getUuid(), safe));
        }

        @Override
        public void onCharacteristicChanged(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value) {
            byte[] safe = value == null
                    ? new byte[0]
                    : Arrays.copyOf(value, value.length);
            handler.post(() ->
                    handleCharacteristicChanged(characteristic.getUuid(), safe));
        }
    };

    @SuppressLint("MissingPermission")
    private void handleConnectionState(BluetoothGatt callbackGatt,
                                       int status,
                                       int newState) {
        if (callbackGatt != gatt) return;

        if (status == BluetoothGatt.GATT_SUCCESS
                && newState == BluetoothProfile.STATE_CONNECTED) {
            handler.removeCallbacks(connectTimeoutRunnable);
            handler.postDelayed(
                    connectTimeoutRunnable,
                    CONNECT_TIMEOUT_MS);

            setState(State.DISCOVERING);

            try {
                if (!callbackGatt.discoverServices()) {
                    fail("GATT-Dienste konnten nicht abgefragt werden");
                }
            } catch (RuntimeException exception) {
                fail("GATT-Dienstsuche fehlgeschlagen: "
                        + exception.getClass().getSimpleName());
            }
            return;
        }

        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            boolean wasIntentional = intentionalDisconnect;
            int disconnectStatus = status;
            closeGatt(false);

            if (!wasIntentional && listener != null) {
                listener.onDisconnected(disconnectStatus);
            }
            return;
        }

        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("GATT-Verbindungsfehler " + status);
        }
    }

    private void handleServicesDiscovered(BluetoothGatt callbackGatt,
                                          int status) {
        if (callbackGatt != gatt) return;

        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("GATT-Dienstsuche fehlgeschlagen: " + status);
            return;
        }

        if (findCharacteristic(S400GattProtocol.UPNP) == null
                || findCharacteristic(S400GattProtocol.AVDTP) == null
                || findCharacteristic(S400GattProtocol.CMTP) == null) {
            fail("Erforderliche S400-GATT-Characteristics fehlen");
            return;
        }

        setState(State.SUBSCRIBING);
        subscribeIndex = 0;
        subscribeNext();
    }

    @SuppressLint("MissingPermission")
    private void subscribeNext() {
        if (gatt == null) return;

        while (subscribeIndex < NOTIFY_CHARACTERISTICS.size()) {
            UUID uuid = NOTIFY_CHARACTERISTICS.get(subscribeIndex++);
            BluetoothGattCharacteristic characteristic =
                    findCharacteristic(uuid);

            boolean required =
                    S400GattProtocol.UPNP.equals(uuid)
                            || S400GattProtocol.AVDTP.equals(uuid)
                            || S400GattProtocol.CMTP.equals(uuid);

            if (characteristic == null) {
                if (required) {
                    fail("Erforderliche GATT-Characteristic fehlt: " + uuid);
                    return;
                }
                continue;
            }

            int properties = characteristic.getProperties();
            boolean supportsNotify =
                    (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
            boolean supportsIndicate =
                    (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;

            if (!supportsNotify && !supportsIndicate) {
                if (required) {
                    fail("S400-Characteristic unterstützt keine Benachrichtigung: "
                            + uuid);
                    return;
                }
                continue;
            }

            try {
                if (!gatt.setCharacteristicNotification(characteristic, true)) {
                    if (required) {
                        fail("GATT-Benachrichtigung konnte nicht aktiviert werden: "
                                + uuid);
                        return;
                    }
                    continue;
                }

                BluetoothGattDescriptor descriptor =
                        characteristic.getDescriptor(
                                S400GattProtocol.CLIENT_CHARACTERISTIC_CONFIG);

                if (descriptor == null) {
                    if (required) {
                        fail("CCCD für S400-Characteristic fehlt: " + uuid);
                        return;
                    }
                    continue;
                }

                byte[] descriptorValue = supportsIndicate && !supportsNotify
                        ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;

                if (!startDescriptorWrite(descriptor, descriptorValue)) {
                    if (required) {
                        fail("CCCD konnte nicht geschrieben werden: " + uuid);
                        return;
                    }
                    continue;
                }

                return;
            } catch (RuntimeException exception) {
                if (required) {
                    fail("GATT-Benachrichtigung fehlgeschlagen: "
                            + exception.getClass().getSimpleName());
                    return;
                }
            }
        }

        handler.postDelayed(this::beginAuthentication, DEVICE_SETTLE_MS);
    }

    private void handleDescriptorWrite(BluetoothGatt callbackGatt,
                                       BluetoothGattDescriptor descriptor,
                                       int status) {
        if (callbackGatt != gatt || state != State.SUBSCRIBING) return;

        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("GATT-CCCD-Schreibfehler " + status);
            return;
        }

        subscribeNext();
    }

    private void beginAuthentication() {
        if (gatt == null || state != State.SUBSCRIBING) return;

        setState(State.AUTHENTICATING);
        authStep = AuthStep.IDLE;
        appRandom = new byte[16];
        secureRandom.nextBytes(appRandom);
        touchAuthTimeout();

        enqueueWrite(
                S400GattProtocol.UPNP,
                S400GattProtocol.CMD_LOGIN,
                () -> {
                    authStep = AuthStep.WAIT_KEY_READY;
                    touchAuthTimeout();
                    enqueueWrite(
                            S400GattProtocol.AVDTP,
                            S400GattProtocol.CMD_SEND_KEY,
                            null,
                            0L);
                },
                0L);
    }

    private void handleCharacteristicChanged(UUID uuid, byte[] value) {
        if (value == null || value.length == 0) return;

        if (S400GattProtocol.AVDTP.equals(uuid)) {
            handleAvdtp(value);
            return;
        }

        if (S400GattProtocol.UPNP.equals(uuid)) {
            handleUpnp(value);
            return;
        }

        if (S400GattProtocol.CMTP.equals(uuid)) {
            handleCmtp(value);
        }
    }

    private void handleAvdtp(byte[] value) {
        if (state != State.AUTHENTICATING) return;

        touchAuthTimeout();

        switch (authStep) {
            case WAIT_KEY_READY:
                if (!Arrays.equals(value, S400GattProtocol.RCV_RDY)) {
                    fail("Unerwartete S400-Antwort vor Login-Key: "
                            + hex(value));
                    return;
                }

                authStep = AuthStep.WAIT_KEY_OK;
                writeParcel(
                        S400GattProtocol.AVDTP,
                        appRandom,
                        null);
                return;

            case WAIT_KEY_OK:
                if (!Arrays.equals(value, S400GattProtocol.RCV_OK)) {
                    fail("S400 hat Login-Key nicht bestätigt: " + hex(value));
                    return;
                }

                authStep = AuthStep.WAIT_DEVICE_RANDOM_HEADER;
                return;

            case WAIT_DEVICE_RANDOM_HEADER:
                if (!beginMultiframe(value)) {
                    fail("Ungültiger S400 Device-Random-Header: " + hex(value));
                    return;
                }

                authStep = AuthStep.RECEIVE_DEVICE_RANDOM;
                enqueueWrite(
                        S400GattProtocol.AVDTP,
                        S400GattProtocol.RCV_RDY,
                        null,
                        0L);
                return;

            case RECEIVE_DEVICE_RANDOM:
                if (!appendMultiframe(value)) return;

                deviceRandom = receiveBuffer.toByteArray();
                if (deviceRandom.length != 16) {
                    fail("S400 Device-Random hat "
                            + deviceRandom.length
                            + " statt 16 Bytes");
                    return;
                }

                authStep = AuthStep.WAIT_REMOTE_INFO_HEADER;
                enqueueWrite(
                        S400GattProtocol.AVDTP,
                        S400GattProtocol.RCV_OK,
                        null,
                        0L);
                return;

            case WAIT_REMOTE_INFO_HEADER:
                if (!beginMultiframe(value)) {
                    fail("Ungültiger S400 Remote-Info-Header: " + hex(value));
                    return;
                }

                authStep = AuthStep.RECEIVE_REMOTE_INFO;
                enqueueWrite(
                        S400GattProtocol.AVDTP,
                        S400GattProtocol.RCV_RDY,
                        null,
                        0L);
                return;

            case RECEIVE_REMOTE_INFO:
                if (!appendMultiframe(value)) return;

                remoteInfo = receiveBuffer.toByteArray();
                verifyRemoteInfoAndContinue();
                return;

            case WAIT_INFO_READY:
                if (!Arrays.equals(value, S400GattProtocol.RCV_RDY)) {
                    fail("S400 ist für Login-Info nicht bereit: " + hex(value));
                    return;
                }

                authStep = AuthStep.WAIT_INFO_OK;
                writeParcel(
                        S400GattProtocol.AVDTP,
                        ourInfo,
                        null);
                return;

            case WAIT_INFO_OK:
                if (!Arrays.equals(value, S400GattProtocol.RCV_OK)) {
                    fail("S400 hat Login-Info nicht bestätigt: " + hex(value));
                    return;
                }

                authStep = AuthStep.WAIT_LOGIN_RESULT;
                return;

            default:
                return;
        }
    }

    private void verifyRemoteInfoAndContinue() {
        try {
            sessionKeys = S400GattCrypto.deriveLoginKeys(
                    token,
                    appRandom,
                    deviceRandom);

            byte[] expectedRemote = S400GattCrypto.hmacSha256(
                    sessionKeys.deviceKey,
                    S400GattCrypto.concat(deviceRandom, appRandom));

            if (!Arrays.equals(remoteInfo, expectedRemote)) {
                fail("Mi-Home-v2-Anmeldung abgelehnt: Login-Token passt nicht");
                return;
            }

            ourInfo = S400GattCrypto.hmacSha256(
                    sessionKeys.appKey,
                    S400GattCrypto.concat(appRandom, deviceRandom));

            authStep = AuthStep.WAIT_INFO_READY;
            touchAuthTimeout();

            enqueueWrite(
                    S400GattProtocol.AVDTP,
                    S400GattProtocol.RCV_OK,
                    () -> enqueueWrite(
                            S400GattProtocol.AVDTP,
                            S400GattProtocol.CMD_SEND_INFO,
                            null,
                            0L),
                    0L);
        } catch (RuntimeException exception) {
            fail("Mi-Home-v2-Schlüsselableitung fehlgeschlagen: "
                    + exception.getClass().getSimpleName());
        }
    }

    private void handleUpnp(byte[] value) {
        if (state != State.AUTHENTICATING
                || authStep != AuthStep.WAIT_LOGIN_RESULT) {
            return;
        }

        touchAuthTimeout();

        if (!Arrays.equals(value, S400GattProtocol.CFM_LOGIN_OK)) {
            fail("Mi-Home-v2-Login fehlgeschlagen: " + hex(value));
            return;
        }

        handler.removeCallbacks(authTimeoutRunnable);
        handler.removeCallbacks(connectTimeoutRunnable);
        authStep = AuthStep.READY;
        setState(State.READY);

        if (listener != null) {
            listener.onAuthenticated();
        }
    }

    private boolean beginMultiframe(byte[] header) {
        if (header.length < 6
                || header[0] != 0
                || header[1] != 0
                || header[2] != 0) {
            return false;
        }

        receiveExpectedFrames =
                (header[4] & 0xff) | ((header[5] & 0xff) << 8);

        if (receiveExpectedFrames <= 0) return false;

        receiveFrames = 0;
        receiveBuffer = new ByteArrayOutputStream();
        return true;
    }

    private boolean appendMultiframe(byte[] frame) {
        if (receiveBuffer == null || frame.length < 2) {
            fail("Ungültiger S400 Mehrfachrahmen");
            return false;
        }

        receiveBuffer.write(frame, 2, frame.length - 2);
        receiveFrames++;

        return receiveFrames >= receiveExpectedFrames;
    }

    private void handleCmtp(byte[] value) {
        if (!isReady()) return;

        if (value.length >= 6
                && value[0] == 0
                && value[1] == 0
                && value[2] == 0
                && value[5] == 0) {
            cmtpExpectedFrames = value[4] & 0xff;
            cmtpBuffer = new ByteArrayOutputStream();

            if (cmtpExpectedFrames > 0) {
                enqueueWrite(
                        S400GattProtocol.CMTP,
                        S400GattProtocol.RCV_RDY,
                        null,
                        0L);
            }
            return;
        }

        if (cmtpExpectedFrames <= 0
                || value.length < 2
                || value[1] != 0) {
            return;
        }

        int frameNumber = value[0] & 0xff;
        cmtpBuffer.write(value, 2, value.length - 2);

        if (frameNumber < cmtpExpectedFrames) return;

        byte[] encrypted = cmtpBuffer.toByteArray();
        cmtpExpectedFrames = 0;
        cmtpBuffer = new ByteArrayOutputStream();

        byte[] plaintext =
                S400GattCrypto.decryptCmtp(sessionKeys, encrypted);

        if (plaintext != null) {
            S400GattMeasurement measurement =
                    S400GattMeasurement.parse(plaintext);

            if (measurement != null && listener != null) {
                listener.onMeasurement(measurement);
            }
        }

        enqueueWrite(
                S400GattProtocol.CMTP,
                S400GattProtocol.RCV_OK,
                null,
                0L);
    }

    private void writeParcel(UUID uuid,
                             byte[] data,
                             Runnable completed) {
        if (data == null) {
            fail("Leerer S400-Datenblock");
            return;
        }

        int chunkSize = 18;
        int chunks = (data.length + chunkSize - 1) / chunkSize;

        for (int index = 0; index < chunks; index++) {
            int from = index * chunkSize;
            int to = Math.min(data.length, from + chunkSize);
            int frameNumber = index + 1;

            byte[] payload = Arrays.copyOfRange(data, from, to);
            byte[] framed = new byte[payload.length + 2];
            framed[0] = (byte) (frameNumber & 0xff);
            framed[1] = (byte) ((frameNumber >> 8) & 0xff);
            System.arraycopy(
                    payload,
                    0,
                    framed,
                    2,
                    payload.length);

            Runnable frameCompleted =
                    index == chunks - 1 ? completed : null;

            enqueueWrite(
                    uuid,
                    framed,
                    frameCompleted,
                    PARCEL_FRAME_DELAY_MS);
        }
    }

    private void enqueueWrite(UUID uuid,
                              byte[] value,
                              Runnable completed,
                              long delayAfterMs) {
        if (gatt == null) return;

        writeQueue.add(new WriteRequest(
                uuid,
                value,
                completed,
                delayAfterMs));

        drainWrites();
    }

    @SuppressLint("MissingPermission")
    private void drainWrites() {
        if (gatt == null || writeInProgress) return;

        currentWrite = writeQueue.poll();
        if (currentWrite == null) return;

        BluetoothGattCharacteristic characteristic =
                findCharacteristic(currentWrite.characteristicUuid);

        if (characteristic == null) {
            fail("GATT-Characteristic zum Schreiben fehlt: "
                    + currentWrite.characteristicUuid);
            return;
        }

        int writeType =
                (characteristic.getProperties()
                        & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

        boolean started;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                started = gatt.writeCharacteristic(
                        characteristic,
                        currentWrite.value,
                        writeType) == BluetoothStatusCodes.SUCCESS;
            } else {
                characteristic.setWriteType(writeType);
                characteristic.setValue(currentWrite.value);
                started = gatt.writeCharacteristic(characteristic);
            }
        } catch (RuntimeException exception) {
            fail("GATT-Schreibvorgang fehlgeschlagen: "
                    + exception.getClass().getSimpleName());
            return;
        }

        if (!started) {
            fail("Android hat den GATT-Schreibvorgang abgelehnt");
            return;
        }

        writeInProgress = true;
    }

    private void handleCharacteristicWrite(
            BluetoothGatt callbackGatt,
            BluetoothGattCharacteristic characteristic,
            int status) {
        if (callbackGatt != gatt || !writeInProgress) return;

        WriteRequest completed = currentWrite;
        currentWrite = null;
        writeInProgress = false;

        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("GATT-Schreibfehler "
                    + status
                    + " bei "
                    + characteristic.getUuid());
            return;
        }

        if (completed != null && completed.completed != null) {
            completed.completed.run();
        }

        long delay = completed == null ? 0L : completed.delayAfterMs;
        if (delay > 0L) {
            handler.postDelayed(this::drainWrites, delay);
        } else {
            drainWrites();
        }
    }

    @SuppressLint("MissingPermission")
    private boolean startDescriptorWrite(BluetoothGattDescriptor descriptor,
                                         byte[] value) {
        if (gatt == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return gatt.writeDescriptor(descriptor, value)
                    == BluetoothStatusCodes.SUCCESS;
        }

        descriptor.setValue(value);
        return gatt.writeDescriptor(descriptor);
    }

    private BluetoothGattCharacteristic findCharacteristic(UUID uuid) {
        if (gatt == null) return null;

        for (BluetoothGattService service : gatt.getServices()) {
            BluetoothGattCharacteristic characteristic =
                    service.getCharacteristic(uuid);

            if (characteristic != null) {
                return characteristic;
            }
        }

        return null;
    }

    private void touchAuthTimeout() {
        handler.removeCallbacks(authTimeoutRunnable);
        handler.postDelayed(authTimeoutRunnable, AUTH_TIMEOUT_MS);
    }

    private void setState(State newState) {
        if (state == newState) return;

        state = newState;
        if (listener != null) {
            listener.onStateChanged(newState);
        }
    }

    private void fail(String message) {
        handler.removeCallbacks(authTimeoutRunnable);
        handler.removeCallbacks(connectTimeoutRunnable);

        if (listener != null) {
            listener.onError(message);
        }

        intentionalDisconnect = true;
        closeGatt(true);
    }

    @SuppressLint("MissingPermission")
    private void closeGatt(boolean requestDisconnect) {
        handler.removeCallbacks(authTimeoutRunnable);
        handler.removeCallbacks(connectTimeoutRunnable);

        BluetoothGatt oldGatt = gatt;
        gatt = null;

        if (oldGatt != null) {
            try {
                if (requestDisconnect) {
                    oldGatt.disconnect();
                }
            } catch (RuntimeException ignored) {
            }

            try {
                oldGatt.close();
            } catch (RuntimeException ignored) {
            }
        }

        resetProtocolState();
        setState(State.DISCONNECTED);
    }

    private void resetProtocolState() {
        authStep = AuthStep.IDLE;
        sessionKeys = null;
        appRandom = null;
        deviceRandom = null;
        remoteInfo = null;
        ourInfo = null;

        receiveExpectedFrames = 0;
        receiveFrames = 0;
        receiveBuffer = null;

        cmtpExpectedFrames = 0;
        cmtpBuffer = new ByteArrayOutputStream();

        writeQueue.clear();
        currentWrite = null;
        writeInProgress = false;

        subscribeIndex = 0;
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();

        for (byte item : value) {
            result.append(String.format("%02X", item & 0xff));
        }

        return result.toString();
    }
}
