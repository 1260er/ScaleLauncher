package de.pritcloud.scalelauncher;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ScaleScanService extends Service {
    public static final String ACTION_STOP = "de.pritcloud.scalelauncher.STOP";
    public static final String ACTION_ASSIGN_PENDING = "de.pritcloud.scalelauncher.ASSIGN_PENDING";
    public static final String ACTION_SYNC_PEERS = "de.pritcloud.scalelauncher.SYNC_PEERS";
    public static final String ACTION_SELECT_PENDING = "de.pritcloud.scalelauncher.SELECT_PENDING";
    public static final String ACTION_REJECT_PENDING = "de.pritcloud.scalelauncher.REJECT_PENDING";
    public static final String ACTION_ACCEPT_REMOTE_PENDING = "de.pritcloud.scalelauncher.ACCEPT_REMOTE_PENDING";
    public static final String ACTION_REJECT_REMOTE_PENDING = "de.pritcloud.scalelauncher.REJECT_REMOTE_PENDING";
    public static final String EXTRA_PENDING_ID = "pending_id";
    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_PROFILE_ID = "profile_id";
    public static final String EXTRA_OWNER_DEVICE_ID = "owner_device_id";

    private static final String CHANNEL_MONITOR = "scale_monitor_v10";
    private static final String CHANNEL_RESULT = "scale_measurement_results_v1";
    private static final String LEGACY_CHANNEL_ASSIGNMENT = "scale_assignment_v1";
    private static final String LEGACY_CHANNEL_FAILURE = "scale_measurement_failure_v1";
    private static final int NOTIFICATION_MONITOR = 10;
    private static final int NOTIFICATION_ASSIGNMENT = 11;
    private static final int NOTIFICATION_RESULT = 12;
    private static final int LEGACY_NOTIFICATION_TRANSFER_FAILURE = 13;
    private static final long WATCHDOG_INTERVAL_MS = 15_000L;
    private static final long REMOTE_COLLECTOR_REACHABLE_MS = 60_000L;
    private static final long GATT_RECONNECT_BASE_MS = 5_000L;
    private static final long GATT_RECONNECT_MAX_MS = 60_000L;
    private static final long USER_SYNC_INTERVAL_MS = 15 * 60_000L;
    private static final long PEER_SYNC_RETRY_MS = 30_000L;
    private static final long PEER_SYNC_ERROR_RETRY_BASE_MS = 2_000L;
    private static final long PEER_SYNC_ERROR_RETRY_SPAN_MS = 4_000L;
    private static final boolean ENABLE_REVERSE_ACK_FALLBACK = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdogRunnable = this::runWatchdog;
    private final Runnable gattReconnectRunnable = this::runGattReconnect;
    private boolean userSyncCompletedOnce;

    private final Runnable userSyncRunnable = new Runnable() {
        @Override public void run() {
            synchronizeOpenScaleUsers();

            if (userSyncCompletedOnce) {
                setCollectorOwned(
                        gattCollectorOwned,
                        true);
            } else {
                userSyncCompletedOnce =
                        true;
            }

            if (!explicitStop) {
                handler.postDelayed(this, USER_SYNC_INTERVAL_MS);
            }
        }
    };

    private final Runnable peerSyncRunnable =
            this::dispatchPeerOutbox;

    private final ArrayDeque<DirectPeerMessage> peerDirectQueue =
            new ArrayDeque<>();

    private final Map<String, Long> remoteCollectorLastSeenMs =
            new HashMap<>();

    private final BroadcastReceiver bluetoothStateReceiver =
            new BroadcastReceiver() {
                @Override public void onReceive(
                        Context context,
                        Intent intent) {
                    if (intent == null
                            || !BluetoothAdapter.ACTION_STATE_CHANGED.equals(
                                    intent.getAction())) {
                        return;
                    }

                    int state =
                            intent.getIntExtra(
                                    BluetoothAdapter.EXTRA_STATE,
                                    BluetoothAdapter.ERROR);

                    if (state == BluetoothAdapter.STATE_TURNING_OFF
                            || state == BluetoothAdapter.STATE_OFF) {
                        EventLog.debug(
                                ScaleScanService.this,
                                "Peer-Transport: Bluetooth aus – Transport wird angehalten");
                        stopPeerTransport(
                                false);

                        setCollectorOwned(
                                false,
                                false);

                        ServiceState.heartbeat(
                                ScaleScanService.this,
                                false,
                                monitorText,
                                false,
                                collectorSource());
                        notifyMonitor();

                        return;
                    }

                    if (state == BluetoothAdapter.STATE_ON) {
                        EventLog.debug(
                                ScaleScanService.this,
                                "Peer-Transport: Bluetooth aktiv – Transport wird neu gestartet");
                        restartPeerTransport();
                    }
                }
            };

    private boolean bluetoothStateReceiverRegistered;

    private S400GattClient gattClient;
    private PeerMeasurementTransport peerTransport;
    private boolean peerSendInFlight;
    private boolean gattMonitoringActive;
    private boolean gattCollectorOwned;
    private boolean collectorStatusAnnounced;
    private boolean gattReconnectScheduled;
    private int gattReconnectAttempt;
    private long lastGattFinalTimestampSeconds;
    private boolean explicitStop;
    private boolean terminalError;
    private String monitorText = "";

    public static void clearTransientNotifications(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(
                        Context.NOTIFICATION_SERVICE);

        if (manager == null) return;

        manager.cancel(NOTIFICATION_ASSIGNMENT);
        manager.cancel(NOTIFICATION_RESULT);
        manager.cancel(LEGACY_NOTIFICATION_TRANSFER_FAILURE);
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannels();

        registerBluetoothStateReceiver();
        startPeerTransport();

        repairStaleAmbiguousPending();

        schedulePeerSync(
                1_000L);

        monitorText = getString(R.string.service_gatt_connecting);
        ServiceState.starting(
                this,
                getString(R.string.service_gatt_connecting));
        startForeground(NOTIFICATION_MONITOR, monitorNotification(monitorText));
        EventLog.info(this, getString(R.string.log_service_started));
        updateAssignmentNotification();
        handler.post(userSyncRunnable);
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            explicitStop = true;
            ServiceState.stopped(this, getString(R.string.service_stopped_by_user));
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_ASSIGN_PENDING.equals(intent.getAction())) {
            String pendingId = intent.getStringExtra(EXTRA_PENDING_ID);
            long userId = intent.getLongExtra(EXTRA_USER_ID, -1L);
            assignPending(pendingId, userId);
        } else if (intent != null
                && ACTION_SELECT_PENDING.equals(
                        intent.getAction())) {
            selectPendingCandidate(
                    intent.getStringExtra(
                            EXTRA_PENDING_ID),
                    intent.getStringExtra(
                            EXTRA_PROFILE_ID),
                    intent.getStringExtra(
                            EXTRA_OWNER_DEVICE_ID));
        } else if (intent != null
                && ACTION_REJECT_PENDING.equals(
                        intent.getAction())) {
            rejectLocalPendingCandidates(
                    intent.getStringExtra(
                            EXTRA_PENDING_ID));
        } else if (intent != null
                && ACTION_ACCEPT_REMOTE_PENDING.equals(
                        intent.getAction())) {
            acceptRemotePending(
                    intent.getStringExtra(
                            EXTRA_PENDING_ID),
                    intent.getStringExtra(
                            EXTRA_PROFILE_ID));
        } else if (intent != null
                && ACTION_REJECT_REMOTE_PENDING.equals(
                        intent.getAction())) {
            rejectRemotePending(
                    intent.getStringExtra(
                            EXTRA_PENDING_ID));
        } else if (intent != null
                && ACTION_SYNC_PEERS.equals(
                        intent.getAction())) {
            refreshTrustedPeerPresence();

            schedulePeerSync(
                    100L);
        } else {
            terminalError = false;
        }
        if (!terminalError) startGattCollector();
        return START_STICKY;
    }

    private PeerMeasurementTransport.Listener createPeerTransportListener() {
        return new PeerMeasurementTransport.Listener() {
                            @Override
                            public void onMessageReceived(
                                    PeerTrustStore.Peer peer,
                                    String payload) {
                                handlePeerMessage(
                                        peer,
                                        payload);
                            }

                            @Override
                            public void onMessageSent(
                                    PeerTrustStore.Peer peer,
                                    String messageId) {
                                peerSendInFlight =
                                        false;

                                DirectPeerMessage direct =
                                        peerDirectQueue.peek();

                                if (direct != null
                                        && direct.messageId.equals(
                                                messageId)) {
                                    peerDirectQueue.poll();

                                    /*
                                     * Direct messages are ACKs. They are not
                                     * persisted and may be followed by the
                                     * next queued message immediately.
                                     */
                                    schedulePeerSync(
                                            250L);
                                    return;
                                }

                                /*
                                 * Persistent outbox messages stay queued until
                                 * their application-level ACK arrives. Do not
                                 * resend them immediately after a successful
                                 * transport write; wait for the normal retry
                                 * interval. Receiving the ACK schedules the
                                 * next outbox item immediately.
                                 */
                                schedulePeerSync(
                                        PEER_SYNC_RETRY_MS);
                            }

                            @Override
                            public void onPeerPresence(
                                    PeerTrustStore.Peer peer,
                                    boolean collector) {
                                ServiceState.CollectorSource previousSource =
                                        collectorSource();

                                if (collector) {
                                    remoteCollectorLastSeenMs.put(
                                            peer.deviceId,
                                            SystemClock.elapsedRealtime());
                                } else {
                                    remoteCollectorLastSeenMs.remove(
                                            peer.deviceId);
                                }

                                ServiceState.CollectorSource currentSource =
                                        collectorSource();

                                if (currentSource != previousSource) {
                                    ServiceState.heartbeat(
                                            ScaleScanService.this,
                                            gattCollectorOwned,
                                            monitorText,
                                            false,
                                            currentSource);
                                    notifyMonitor();
                                }
                            }

                            @Override
                            public void onError(
                                    String message) {
                                peerSendInFlight =
                                        false;

                                EventLog.warning(
                                        ScaleScanService.this,
                                        getString(
                                                R.string.log_peer_transport_error,
                                                message));

                                schedulePeerSync(
                                        peerErrorRetryDelayMs());
                            }
                        };
    }

    private void registerBluetoothStateReceiver() {
        if (bluetoothStateReceiverRegistered) {
            return;
        }

        IntentFilter filter =
                new IntentFilter(
                        BluetoothAdapter.ACTION_STATE_CHANGED);

        if (android.os.Build.VERSION.SDK_INT
                >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                    bluetoothStateReceiver,
                    filter,
                    Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(
                    bluetoothStateReceiver,
                    filter);
        }

        bluetoothStateReceiverRegistered = true;
    }

    private void refreshTrustedPeerPresence() {
        ServiceState.CollectorSource previousSource =
                collectorSource();

        if (peerTransport != null) {
            peerTransport.refreshPresencePeers();
        }

        List<PeerTrustStore.Peer> trustedPeers =
                PeerTrustStore.load(
                        this);

        remoteCollectorLastSeenMs.keySet()
                .removeIf(
                        deviceId ->
                                trustedPeers.stream()
                                        .noneMatch(
                                                peer ->
                                                        peer.deviceId.equals(
                                                                deviceId)));

        ServiceState.CollectorSource currentSource =
                collectorSource();

        if (currentSource != previousSource) {
            ServiceState.heartbeat(
                    this,
                    gattCollectorOwned,
                    monitorText,
                    false,
                    currentSource);
        }
    }

    private void startPeerTransport() {
        if (peerTransport != null
                || explicitStop) {
            return;
        }

        peerTransport =
                new PeerMeasurementTransport(
                        this,
                        createPeerTransportListener());

        peerTransport.setCollectorAdvertising(
                gattCollectorOwned);

        peerTransport.start();
    }

    private void stopPeerTransport(
            boolean updateCollectorState) {
        ServiceState.CollectorSource previousSource =
                collectorSource();

        if (peerTransport != null) {
            peerTransport.stop();
            peerTransport = null;
        }

        remoteCollectorLastSeenMs.clear();
        peerSendInFlight = false;

        if (updateCollectorState) {
            ServiceState.CollectorSource currentSource =
                    collectorSource();

            if (currentSource != previousSource) {
                ServiceState.heartbeat(
                        this,
                        gattCollectorOwned,
                        monitorText,
                        false,
                        currentSource);
            }
        }
    }

    private void restartPeerTransport() {
        stopPeerTransport(
                true);

        if (explicitStop) {
            return;
        }

        startPeerTransport();
        schedulePeerSync(
                100L);
    }

    private void runGattReconnect() {
        gattReconnectScheduled = false;
        if (gattMonitoringActive && !explicitStop && !terminalError) {
            connectGattCollector();
        }
    }

    private void startGattCollector() {
        if (gattMonitoringActive) {
            if (gattClient == null && !gattReconnectScheduled) {
                connectGattCollector();
            }
            return;
        }

        if (!PowerSettingsHelper.isBatteryOptimizationDisabled(this)) {
            enterTerminalError(
                    getString(R.string.service_error_battery_optimization));
            return;
        }
        if (!PowerSettingsHelper.isUnusedAppManagementDisabled(this)) {
            enterTerminalError(
                    getString(R.string.service_error_unused_app_management));
            return;
        }
        if (!PowerSettingsHelper.areNotificationsUsable(this)) {
            enterTerminalError(
                    getString(R.string.service_error_notifications));
            return;
        }

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            enterTerminalError(
                    getString(R.string.service_error_bluetooth_permission));
            return;
        }

        SharedPreferences prefs =
                getSharedPreferences("prefs", MODE_PRIVATE);
        String mac = prefs.getString("mac", "");
        String token = prefs.getString("login_token", "");
        String authority =
                prefs.getString("openscale_authority", "");
        List<UserProfile> profiles =
                UserProfileStore.enabled(UserProfileStore.load(prefs));

        if (!S400GattProtocol.isValidMacAddress(mac)) {
            enterTerminalError(
                    getString(R.string.service_error_invalid_mac));
            return;
        }
        if (!S400GattProtocol.isValidLoginToken(token)) {
            enterTerminalError(
                    getString(R.string.scale_error_invalid_login_token));
            return;
        }
        if (authority == null || authority.isBlank()) {
            enterTerminalError(
                    getString(R.string.service_error_no_openscale_connection));
            return;
        }

        OpenScaleProvider.Meta providerMeta;
        try {
            providerMeta =
                    OpenScaleProvider.readMeta(this, authority);
        } catch (SecurityException exception) {
            enterTerminalError(
                    getString(R.string.service_error_openscale_permission));
            return;
        } catch (RuntimeException exception) {
            enterTerminalError(
                    getString(R.string.service_error_openscale_unreachable));
            return;
        }

        if (!providerMeta.supportsGenericValues()) {
            enterTerminalError(
                    getString(R.string.service_error_provider_api));
            return;
        }
        if (profiles.isEmpty()) {
            enterTerminalError(
                    getString(R.string.service_error_no_active_profile));
            return;
        }

        long now = System.currentTimeMillis();
        for (UserProfile profile : profiles) {
            if (!profile.hasValidBodyData(now)
                    || !profile.hasValidMatchingData()) {
                enterTerminalError(getString(
                        R.string.service_error_incomplete_profile,
                        profile.name));
                return;
            }
        }

        gattMonitoringActive = true;
        setCollectorOwned(
                false,
                true);
        gattReconnectAttempt = 0;

        EventLog.info(
                this,
                getString(R.string.log_gatt_collector_started));

        connectGattCollector();
    }

    private void connectGattCollector() {
        if (!gattMonitoringActive
                || explicitStop
                || terminalError
                || gattClient != null) {
            return;
        }

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            enterTerminalError(
                    getString(R.string.service_error_bluetooth_permission));
            return;
        }

        SharedPreferences prefs =
                getSharedPreferences("prefs", MODE_PRIVATE);
        String mac = prefs.getString("mac", "");
        String token = prefs.getString("login_token", "");

        if (!S400GattProtocol.isValidMacAddress(mac)) {
            enterTerminalError(
                    getString(R.string.service_error_invalid_mac));
            return;
        }
        if (!S400GattProtocol.isValidLoginToken(token)) {
            enterTerminalError(
                    getString(R.string.scale_error_invalid_login_token));
            return;
        }

        BluetoothManager manager =
                (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter =
                manager == null ? null : manager.getAdapter();

        if (adapter == null || !adapter.isEnabled()) {
            enterRecoverableError(
                    getString(R.string.service_error_bluetooth_disabled));
            scheduleGattReconnect(
                    getString(R.string.service_error_bluetooth_disabled));
            return;
        }

        final BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(mac);
        } catch (RuntimeException exception) {
            scheduleGattReconnect(
                    exception.getClass().getSimpleName());
            return;
        }

        monitorText = getString(R.string.service_gatt_standby);
        ServiceState.running(
                this,
                monitorText,
                false,
                collectorSource());
        notifyMonitor();

        EventLog.debug(
                this,
                getString(R.string.log_gatt_standby));

        gattClient = new S400GattClient(
                this,
                new S400GattClient.Listener() {
                    @Override public void onStateChanged(
                            S400GattClient.State state) {
                        EventLog.debug(
                                ScaleScanService.this,
                                getString(
                                        R.string.log_gatt_state,
                                        state.name()));

                        if (state == S400GattClient.State.DISCOVERING
                                || state == S400GattClient.State.SUBSCRIBING
                                || state == S400GattClient.State.AUTHENTICATING) {
                            monitorText =
                                    getString(R.string.service_gatt_claiming);
                            ServiceState.running(
                                    ScaleScanService.this,
                                    monitorText,
                                    false,
                                    collectorSource());
                            notifyMonitor();
                        }

                        if (state == S400GattClient.State.DISCONNECTED
                                && gattMonitoringActive) {
                            setCollectorOwned(
                                    false,
                                    false);
                            gattClient = null;
                            scheduleGattReconnect(
                                    getString(
                                            R.string.service_error_gatt_inactive));
                        }
                    }

                    @Override public void onAuthenticated() {
                        setCollectorOwned(
                                true,
                                false);
                        gattReconnectAttempt = 0;
                        ServiceState.scaleSeen(ScaleScanService.this);

                        EventLog.info(
                                ScaleScanService.this,
                                getString(
                                        R.string.log_gatt_authenticated));

                        updateMonitor(
                                getString(R.string.service_gatt_ready));
                    }

                    @Override public void onMeasurement(
                            S400GattMeasurement measurement) {
                        handleGattMeasurement(measurement);
                    }

                    @Override public void onDisconnected(int status) {
                        EventLog.warning(
                                ScaleScanService.this,
                                getString(
                                        R.string.log_gatt_disconnected,
                                        status));
                    }

                    @Override public void onError(String message) {
                        if (gattCollectorOwned) {
                            EventLog.error(
                                    ScaleScanService.this,
                                    getString(
                                            R.string.log_gatt_error,
                                            message));
                        } else {
                            EventLog.warning(
                                    ScaleScanService.this,
                                    getString(
                                            R.string.log_gatt_standby_failure,
                                            message));
                        }
                    }
                });

        gattClient.connect(device, token, true);
    }

    private void handleGattMeasurement(
            S400GattMeasurement measurement) {
        if (measurement == null || measurement.weightKg == null) {
            return;
        }

        ServiceState.scaleSeen(this);

        if (measurement.type == S400GattMeasurement.Type.LIVE) {
            EventLog.debug(
                    this,
                    getString(
                            R.string.log_gatt_live,
                            measurement.weightKg,
                            Boolean.toString(measurement.stable)));
            return;
        }

        String impedance = measurement.impedance == null
                ? "–"
                : String.format(
                        Locale.GERMANY,
                        "%.1f",
                        measurement.impedance);
        String impedanceLow = measurement.impedanceLow == null
                ? "–"
                : String.format(
                        Locale.GERMANY,
                        "%.1f",
                        measurement.impedanceLow);

        if (measurement.impedance == null
                || measurement.impedanceLow == null
                || !Float.isFinite(measurement.weightKg)
                || !Float.isFinite(measurement.impedance)
                || !Float.isFinite(measurement.impedanceLow)
                || measurement.weightKg <= 0f
                || measurement.impedance <= 0f
                || measurement.impedanceLow <= 0f) {
            EventLog.warning(
                    this,
                    getString(
                            R.string.log_gatt_final_incomplete,
                            measurement.weightKg,
                            impedance,
                            impedanceLow));
            rejectMeasurement(
                    getString(
                            R.string.service_error_gatt_final_incomplete));
            return;
        }

        long deviceTimestamp =
                measurement.timestampSeconds == null
                        ? 0L
                        : measurement.timestampSeconds;

        if (deviceTimestamp > 0L
                && deviceTimestamp
                == lastGattFinalTimestampSeconds) {
            EventLog.debug(
                    this,
                    getString(
                            R.string.log_duplicate_measurement_packet));
            return;
        }

        if (deviceTimestamp > 0L) {
            lastGattFinalTimestampSeconds = deviceTimestamp;
        }

        EventLog.info(
                this,
                getString(
                        R.string.log_gatt_final,
                        measurement.weightKg,
                        impedance,
                        impedanceLow));

        long timestampMs = deviceTimestamp > 0L
                ? deviceTimestamp * 1000L
                : System.currentTimeMillis();

        S400FinalMeasurement finalized =
                new S400FinalMeasurement(
                        measurement.weightKg,
                        measurement.impedance,
                        measurement.impedanceLow,
                        timestampMs,
                        measurement.profileId);

        EventLog.info(
                this,
                getString(
                        R.string.log_complete_measurement,
                        finalized.weightKg));
        EventLog.debug(
                this,
                getString(
                        R.string.log_s400_measurement_received,
                        finalized.weightKg,
                        finalized.impedanceHigh,
                        finalized.impedanceLow));

        routeMeasurement(finalized);
    }

    private void handlePeerMessage(
            PeerTrustStore.Peer peer,
            String encoded) {
        if (peer == null
                || encoded == null
                || encoded.isBlank()) {
            return;
        }

        final String type;

        try {
            type =
                    new JSONObject(encoded)
                            .optString(
                                    "type",
                                    "");
        } catch (Exception exception) {
            return;
        }

        if (PeerAckPayload.TYPE.equals(type)) {
            PeerAckPayload ack =
                    PeerAckPayload.decode(
                            encoded);

            if (ack == null) {
                return;
            }

            if (PeerOutboxStore.remove(
                    this,
                    peer.deviceId,
                    ack.acknowledgedMessageId)) {
                EventLog.debug(
                        this,
                        getString(
                                R.string.log_peer_ack_received,
                                peer.label));

                if (ack.acknowledgedMessageId.startsWith(
                        "route:")) {
                    String measurementId =
                            ack.acknowledgedMessageId.substring(
                                    "route:".length());

                    SharedPreferences prefs =
                            getSharedPreferences(
                                    "prefs",
                                    MODE_PRIVATE);

                    PendingMeasurementStore.Item pending =
                            PendingMeasurementStore.find(
                                    prefs,
                                    measurementId);

                    if (pending != null
                            && pending.isResolved()
                            && peer.deviceId.equals(
                                    pending.selectedOwnerDeviceId)) {
                        PendingMeasurementStore.remove(
                                prefs,
                                measurementId);

                        EventLog.info(
                                this,
                                getString(
                                        R.string.log_peer_routed_measurement_confirmed,
                                        peer.label,
                                        measurementId));

                        updateAssignmentNotification();
                    }
                }

                schedulePeerSync(
                        250L);
            }

            return;
        }

        if (PeerProfilePayload.TYPE.equals(type)) {
            PeerProfilePayload payload =
                    PeerProfilePayload.decode(
                            encoded);

            if (payload == null) {
                return;
            }

            boolean duplicate =
                    PeerInboxDedupStore.contains(
                            this,
                            peer.deviceId,
                            payload.messageId);

            if (!duplicate) {
                SharedPreferences prefs =
                        getSharedPreferences(
                                "prefs",
                                MODE_PRIVATE);

                if (!HouseholdProfileSync.acceptIncomingProfile(
                        this,
                        prefs,
                        peer,
                        payload.profile,
                        payload.ownerProfileIds)) {
                    return;
                }

                PeerInboxDedupStore.mark(
                        this,
                        peer.deviceId,
                        payload.messageId);

                EventLog.info(
                        this,
                        getString(
                                R.string.log_peer_profile_received,
                                payload.profile.name,
                                peer.label));
            }

            queuePeerAck(
                    peer,
                    payload.messageId);

            return;
        }

        if (PeerCollectorStatusPayload.TYPE.equals(
                type)) {
            PeerCollectorStatusPayload status =
                    PeerCollectorStatusPayload.decode(
                            encoded);

            if (status == null) {
                return;
            }

            boolean duplicate =
                    PeerInboxDedupStore.contains(
                            this,
                            peer.deviceId,
                            status.messageId);

            if (!duplicate) {
                ServiceState.CollectorSource previousSource =
                        collectorSource();

                if (!status.collector) {
                    remoteCollectorLastSeenMs.remove(
                            peer.deviceId);
                }

                PeerInboxDedupStore.mark(
                        this,
                        peer.deviceId,
                        status.messageId);

                ServiceState.CollectorSource currentSource =
                        collectorSource();

                if (currentSource != previousSource) {
                    ServiceState.heartbeat(
                            this,
                            gattCollectorOwned,
                            monitorText,
                            false,
                            currentSource);
                }
            }

            queuePeerAck(
                    peer,
                    status.messageId);

            return;
        }

        if (PeerMeasurementClosedPayload.TYPE.equals(
                type)) {
            PeerMeasurementClosedPayload closed =
                    PeerMeasurementClosedPayload.decode(
                            encoded);

            if (closed == null) {
                return;
            }

            boolean duplicate =
                    PeerInboxDedupStore.contains(
                            this,
                            peer.deviceId,
                            closed.messageId);

            if (!duplicate) {
                RemotePendingMeasurementStore.Item remote =
                        RemotePendingMeasurementStore.find(
                                this,
                                closed.measurementId);

                boolean removed =
                        remote != null
                                && peer.deviceId.equals(
                                        remote.collectorDeviceId)
                                && RemotePendingMeasurementStore.remove(
                                        this,
                                        closed.measurementId);

                PeerInboxDedupStore.mark(
                        this,
                        peer.deviceId,
                        closed.messageId);

                if (removed) {
                    EventLog.info(
                            this,
                            getString(
                                    R.string.log_remote_pending_closed,
                                    remote.weightKg,
                                    peer.label));

                    updateAssignmentNotification();
                }
            }

            queuePeerAck(
                    peer,
                    closed.messageId);

            return;
        }

        if (PeerMeasurementDecisionPayload.TYPE.equals(
                type)) {
            PeerMeasurementDecisionPayload decision =
                    PeerMeasurementDecisionPayload.decode(
                            encoded);

            if (decision == null) {
                return;
            }

            boolean duplicate =
                    PeerInboxDedupStore.contains(
                            this,
                            peer.deviceId,
                            decision.messageId);

            if (!duplicate) {
                SharedPreferences prefs =
                        getSharedPreferences(
                                "prefs",
                                MODE_PRIVATE);

                PendingMeasurementStore.Item pending =
                        PendingMeasurementStore.find(
                                prefs,
                                decision.measurementId);

                if (pending != null
                        && validIncomingDecision(
                                prefs,
                                peer,
                                pending,
                                decision)) {
                    boolean changed =
                            decision.isAccepted()
                                    ? PendingMeasurementStore.selectCandidate(
                                            prefs,
                                            decision.measurementId,
                                            decision.profileId,
                                            peer.deviceId)
                                    : PendingMeasurementStore.rejectCandidate(
                                            prefs,
                                            decision.measurementId,
                                            decision.profileId);

                    PeerInboxDedupStore.mark(
                            this,
                            peer.deviceId,
                            decision.messageId);

                    if (changed) {
                        EventLog.info(
                                this,
                                getString(
                                        decision.isAccepted()
                                                ? R.string.log_peer_decision_accepted
                                                : R.string.log_peer_decision_rejected,
                                        peer.label,
                                        decision.measurementId));

                        if (decision.isAccepted()) {
                            resolvePendingDecision(
                                    prefs,
                                    decision.measurementId);
                        } else {
                            autoResolveSingleRemainingCandidate(
                                    prefs,
                                    decision.measurementId);

                            removePendingWithoutCandidates(
                                    prefs,
                                    decision.measurementId);
                        }
                    } else {
                        EventLog.debug(
                                this,
                                getString(
                                        R.string.log_peer_decision_ignored,
                                        peer.label,
                                        decision.measurementId));
                    }

                    updateAssignmentNotification();
                } else {
                    EventLog.warning(
                            this,
                            getString(
                                    R.string.log_peer_decision_invalid,
                                    peer.label,
                                    decision.measurementId));
                }
            }

            queuePeerAck(
                    peer,
                    decision.messageId);

            return;
        }

        if (PeerClaimPayload.TYPE.equals(type)) {
            PeerClaimPayload claim =
                    PeerClaimPayload.decode(
                            encoded);

            if (claim == null) {
                return;
            }

            boolean duplicate =
                    PeerInboxDedupStore.contains(
                            this,
                            peer.deviceId,
                            claim.messageId);

            if (!duplicate) {
                SharedPreferences prefs =
                        getSharedPreferences(
                                "prefs",
                                MODE_PRIVATE);

                PendingMeasurementStore.Item pending =
                        PendingMeasurementStore.find(
                                prefs,
                                claim.measurementId);

                if (pending != null
                        && validIncomingClaim(
                                peer,
                                pending,
                                claim.claimedProfileIds)) {
                    PendingMeasurementStore.recordClaimResponse(
                            prefs,
                            claim.measurementId,
                            peer.deviceId,
                            claim.claimedProfileIds);

                    rejectUnclaimedPeerCandidates(
                            prefs,
                            pending,
                            peer.deviceId,
                            claim.claimedProfileIds);

                    autoResolveSingleRemainingCandidate(
                            prefs,
                            claim.measurementId);

                    removePendingWithoutCandidates(
                            prefs,
                            claim.measurementId);

                    PeerInboxDedupStore.mark(
                            this,
                            peer.deviceId,
                            claim.messageId);

                    EventLog.info(
                            this,
                            getString(
                                    R.string.log_peer_claim_received,
                                    peer.label,
                                    claim.measurementId,
                                    claim.claimedProfileIds.size()));
                } else if (pending != null) {
                    EventLog.warning(
                            this,
                            getString(
                                    R.string.log_peer_claim_rejected,
                                    peer.label,
                                    claim.measurementId));
                }
            }

            queuePeerAck(
                    peer,
                    claim.messageId);

            return;
        }

        if (PeerMeasurementPayload.TYPE.equals(type)) {
            PeerMeasurementPayload payload =
                    PeerMeasurementPayload.decode(
                            encoded);

            if (payload == null) {
                return;
            }

            if (payload.requiresClaim) {
                String requestMessageId =
                        payload.transportMessageId();

                String dedupKey =
                        payload.manualRescue
                                ? "rescue-request:"
                                    + payload.measurementId
                                : "claim-request:"
                                    + payload.measurementId;

                boolean duplicate =
                        PeerInboxDedupStore.contains(
                                this,
                                peer.deviceId,
                                dedupKey);

                if (!duplicate) {
                    EventLog.info(
                            this,
                            getString(
                                    R.string.log_peer_measurement_received,
                                    peer.label,
                                    payload.weightKg));

                    handleIncomingClaimRequest(
                            peer,
                            payload);

                    /*
                     * The CLAIM response is persisted in PeerOutboxStore
                     * before the request is marked as processed. After a
                     * restart/retry we can therefore safely suppress another
                     * response and only ACK the repeated request.
                     */
                    PeerInboxDedupStore.mark(
                            this,
                            peer.deviceId,
                            dedupKey);
                }

                queuePeerAck(
                        peer,
                        requestMessageId);

                return;
            }

            if (payload.targetProfileId.isBlank()) {
                EventLog.info(
                        this,
                        getString(
                                R.string.log_peer_measurement_received,
                                peer.label,
                                payload.weightKg));
                return;
            }

            handleIncomingRoutedMeasurement(
                    peer,
                    payload);
        }
    }

    private void handleIncomingRoutedMeasurement(
            PeerTrustStore.Peer peer,
            PeerMeasurementPayload payload) {
        if (peer == null
                || payload == null
                || payload.requiresClaim
                || !UserProfile.isValidHouseholdProfileId(
                        payload.targetProfileId)) {
            return;
        }

        String ackId =
                "route:"
                        + payload.measurementId;

        String dedupKey =
                "routed-measurement:"
                        + payload.measurementId;

        if (PeerInboxDedupStore.contains(
                this,
                peer.deviceId,
                dedupKey)) {
            queuePeerAck(
                    peer,
                    ackId);
            return;
        }

        SharedPreferences prefs =
                getSharedPreferences(
                        "prefs",
                        MODE_PRIVATE);

        String configuredScaleMac =
                prefs.getString(
                        "mac",
                        "");

        String localDeviceId =
                PeerTrustStore.localDeviceId(
                        this);

        List<UserProfile> localProfiles =
                UserProfileStore.enabled(
                        UserProfileStore.load(
                                prefs));

        UserProfile target =
                UserProfileStore.findByHouseholdProfileId(
                        localProfiles,
                        payload.targetProfileId);

        if (target == null
                || !localDeviceId.equals(
                        target.ownerDeviceId)
                || !target.hasValidBodyData(
                        payload.timestampMs)
                || !S400GattProtocol.isValidMacAddress(
                        configuredScaleMac)
                || !configuredScaleMac.equalsIgnoreCase(
                        payload.scaleMac)) {
            EventLog.warning(
                    this,
                    getString(
                            R.string.log_peer_routed_measurement_rejected,
                            peer.label,
                            payload.measurementId));
            return;
        }

        EventLog.info(
                this,
                getString(
                        R.string.log_peer_routed_measurement_received,
                        peer.label,
                        target.name,
                        payload.weightKg));

        if (!processMeasurement(
                payload.toMeasurement(),
                target)) {
            return;
        }

        RemotePendingMeasurementStore.remove(
                this,
                payload.measurementId);

        updateAssignmentNotification();

        PeerInboxDedupStore.mark(
                this,
                peer.deviceId,
                dedupKey);

        queuePeerAck(
                peer,
                ackId);
    }

    private void handleIncomingClaimRequest(
            PeerTrustStore.Peer peer,
            PeerMeasurementPayload payload) {
        if (peer == null
                || payload == null
                || !payload.requiresClaim) {
            return;
        }

        SharedPreferences prefs =
                getSharedPreferences(
                        "prefs",
                        MODE_PRIVATE);

        String localDeviceId =
                PeerTrustStore.localDeviceId(
                        this);

        List<UserProfile> localProfiles =
                UserProfileStore.enabled(
                        UserProfileStore.load(
                                prefs));

        List<String> claimedProfileIds =
                new java.util.ArrayList<>();

        for (String candidateProfileId :
                payload.candidateProfileIds) {
            UserProfile profile =
                    UserProfileStore.findByHouseholdProfileId(
                            localProfiles,
                            candidateProfileId);

            if (profile == null
                    || !localDeviceId.equals(
                            profile.ownerDeviceId)
                    || !profile.hasValidBodyData(
                            payload.timestampMs)) {
                continue;
            }

            if (payload.manualRescue) {
                claimedProfileIds.add(
                        profile.householdProfileId);
                continue;
            }

            if (!profile.hasValidMatchingData()) {
                continue;
            }

            float difference =
                    Math.abs(
                            payload.weightKg
                                    - profile.referenceWeightKg);

            if (difference
                    <= profile.toleranceKg) {
                claimedProfileIds.add(
                        profile.householdProfileId);
            }
        }

        if (!claimedProfileIds.isEmpty()) {
            if (RemotePendingMeasurementStore.upsert(
                    this,
                    peer,
                    payload,
                    claimedProfileIds)) {
                EventLog.debug(
                        this,
                        getString(
                                R.string.log_remote_pending_saved,
                                payload.measurementId,
                                claimedProfileIds.size()));

                updateAssignmentNotification();
            }
        }

        PeerClaimPayload claim =
                PeerClaimPayload.create(
                        payload.measurementId,
                        claimedProfileIds);

        PeerOutboxStore.enqueueClaim(
                this,
                peer.deviceId,
                claim);

        EventLog.debug(
                this,
                getString(
                        R.string.log_peer_claim_response_queued,
                        payload.measurementId,
                        peer.label,
                        claimedProfileIds.size()));

        schedulePeerSync(
                100L);
    }

    private void acceptRemotePending(
            String measurementId,
            String profileId) {
        if (measurementId == null
                || measurementId.isBlank()
                || !UserProfile.isValidHouseholdProfileId(
                        profileId)) {
            return;
        }

        RemotePendingMeasurementStore.Item pending =
                RemotePendingMeasurementStore.find(
                        this,
                        measurementId);

        if (pending == null
                || !pending.candidateProfileIds.contains(
                        profileId)) {
            return;
        }

        PeerTrustStore.Peer collector =
                PeerTrustStore.find(
                        this,
                        pending.collectorDeviceId);

        if (collector == null) {
            return;
        }

        SharedPreferences prefs =
                getSharedPreferences(
                        "prefs",
                        MODE_PRIVATE);

        UserProfile local =
                UserProfileStore.findByHouseholdProfileId(
                        UserProfileStore.enabled(
                                UserProfileStore.load(
                                        prefs)),
                        profileId);

        String localDeviceId =
                PeerTrustStore.localDeviceId(
                        this);

        if (local == null
                || !localDeviceId.equals(
                        local.ownerDeviceId)
                || !local.hasValidBodyData(
                        pending.timestampMs)) {
            return;
        }

        PeerMeasurementDecisionPayload decision =
                PeerMeasurementDecisionPayload.create(
                        pending.measurementId,
                        profileId,
                        true);

        PeerOutboxStore.enqueueDecision(
                this,
                collector.deviceId,
                decision);

        RemotePendingMeasurementStore.remove(
                this,
                pending.measurementId);

        updateAssignmentNotification();

        EventLog.info(
                this,
                getString(
                        R.string.log_remote_pending_accept_queued,
                        pending.weightKg,
                        local.name,
                        collector.label));

        schedulePeerSync(
                100L);
    }

    private void rejectRemotePending(
            String measurementId) {
        if (measurementId == null
                || measurementId.isBlank()) {
            return;
        }

        RemotePendingMeasurementStore.Item pending =
                RemotePendingMeasurementStore.find(
                        this,
                        measurementId);

        if (pending == null) {
            return;
        }

        PeerTrustStore.Peer collector =
                PeerTrustStore.find(
                        this,
                        pending.collectorDeviceId);

        if (collector == null) {
            return;
        }

        int queued =
                0;

        for (String profileId :
                pending.candidateProfileIds) {
            PeerMeasurementDecisionPayload decision =
                    PeerMeasurementDecisionPayload.create(
                            pending.measurementId,
                            profileId,
                            false);

            PeerOutboxStore.enqueueDecision(
                    this,
                    collector.deviceId,
                    decision);

            queued++;
        }

        if (queued <= 0) {
            return;
        }

        RemotePendingMeasurementStore.remove(
                this,
                pending.measurementId);

        updateAssignmentNotification();

        EventLog.info(
                this,
                getString(
                        R.string.log_remote_pending_reject_queued,
                        pending.weightKg,
                        queued,
                        collector.label));

        schedulePeerSync(
                100L);
    }

    private void rejectUnclaimedPeerCandidates(
            SharedPreferences prefs,
            PendingMeasurementStore.Item pending,
            String peerDeviceId,
            List<String> claimedProfileIds) {
        if (prefs == null
                || pending == null
                || !PeerTrustStore.isValidDeviceId(
                        peerDeviceId)) {
            return;
        }

        List<String> claimed =
                claimedProfileIds == null
                        ? List.of()
                        : claimedProfileIds;

        for (String profileId :
                new java.util.ArrayList<>(
                        pending.remainingCandidateProfileIds())) {
            HouseholdProfile profile =
                    HouseholdProfileStore.find(
                            this,
                            profileId);

            if (profile != null
                    && peerDeviceId.equals(
                            profile.ownerDeviceId)
                    && !claimed.contains(
                            profileId)) {
                PendingMeasurementStore.rejectCandidate(
                        prefs,
                        pending.id,
                        profileId);
            }
        }
    }

    private void removePendingWithoutCandidates(
            SharedPreferences prefs,
            String pendingId) {
        PendingMeasurementStore.Item pending =
                PendingMeasurementStore.find(
                        prefs,
                        pendingId);

        if (pending == null
                || pending.isResolved()
                || !pending.remainingCandidateProfileIds()
                        .isEmpty()) {
            return;
        }

        PeerOutboxStore.removeMeasurement(
                this,
                pendingId);

        broadcastMeasurementClosed(
                pendingId);

        PendingMeasurementStore.remove(
                prefs,
                pendingId);

        EventLog.info(
                this,
                getString(
                        R.string.log_pending_no_candidates,
                        pending.weightKg));
    }

    private boolean validIncomingDecision(
            SharedPreferences prefs,
            PeerTrustStore.Peer peer,
            PendingMeasurementStore.Item pending,
            PeerMeasurementDecisionPayload decision) {
        if (prefs == null
                || peer == null
                || pending == null
                || decision == null
                || !decision.isValid()
                || !pending.candidateProfileIds.contains(
                        decision.profileId)) {
            return false;
        }

        boolean ownedByPeer =
                false;

        for (HouseholdProfile profile :
                HouseholdProfileStore.active(
                        this)) {
            if (decision.profileId.equals(
                        profile.profileId)
                    && peer.deviceId.equals(
                            profile.ownerDeviceId)) {
                ownedByPeer =
                        true;
                break;
            }
        }

        if (!ownedByPeer) {
            return false;
        }

        if (!decision.isAccepted()) {
            return true;
        }

        if (pending.manualRescue) {
            return true;
        }

        for (PendingMeasurementStore.ClaimResponse response :
                PendingMeasurementStore.claimResponses(
                        prefs,
                        pending.id)) {
            if (peer.deviceId.equals(
                        response.peerDeviceId)
                    && response.profileIds.contains(
                            decision.profileId)) {
                return true;
            }
        }

        return false;
    }

    private boolean validIncomingClaim(
            PeerTrustStore.Peer peer,
            PendingMeasurementStore.Item pending,
            List<String> claimedProfileIds) {
        if (peer == null
                || pending == null
                || claimedProfileIds == null) {
            return false;
        }

        List<HouseholdProfile> householdProfiles =
                HouseholdProfileStore.active(
                        this);

        for (String profileId :
                claimedProfileIds) {
            if (!pending.candidateProfileIds.contains(
                    profileId)) {
                return false;
            }

            boolean ownedByPeer =
                    false;

            for (HouseholdProfile profile :
                    householdProfiles) {
                if (profileId.equals(
                        profile.profileId)
                        && peer.deviceId.equals(
                                profile.ownerDeviceId)) {
                    ownedByPeer =
                            true;
                    break;
                }
            }

            if (!ownedByPeer) {
                return false;
            }
        }

        return true;
    }

    private boolean enqueueRoutedMeasurement(
            PendingMeasurementStore.Item pending,
            String targetProfileId,
            String targetDeviceId) {
        if (pending == null
                || !UserProfile.isValidHouseholdProfileId(
                        targetProfileId)
                || !PeerTrustStore.isValidDeviceId(
                        targetDeviceId)) {
            return false;
        }

        PeerTrustStore.Peer peer =
                PeerTrustStore.find(
                        this,
                        targetDeviceId);

        if (peer == null) {
            return false;
        }

        SharedPreferences prefs =
                getSharedPreferences(
                        "prefs",
                        MODE_PRIVATE);

        String scaleMac =
                prefs.getString(
                        "mac",
                        "");

        if (!S400GattProtocol.isValidMacAddress(
                scaleMac)) {
            return false;
        }

        try {
            PeerMeasurementPayload payload =
                    PeerMeasurementPayload.forUniqueTarget(
                            scaleMac,
                            pending.toMeasurement(),
                            targetProfileId);

            PeerOutboxStore.enqueueMeasurement(
                    this,
                    targetDeviceId,
                    payload);

            EventLog.info(
                    this,
                    getString(
                            R.string.log_peer_routed_measurement_queued,
                            pending.id,
                            peer.label));

            schedulePeerSync(
                    100L);

            return true;
        } catch (RuntimeException exception) {
            EventLog.warning(
                    this,
                    getString(
                            R.string.log_peer_transport_error,
                            exception.getClass()
                                    .getSimpleName()));
            return false;
        }
    }

    private void setCollectorOwned(
            boolean collector,
            boolean forceAnnounce) {
        boolean changed =
                gattCollectorOwned != collector;

        gattCollectorOwned =
                collector;

        if (peerTransport != null) {
            peerTransport.setCollectorAdvertising(
                    collector);
        }

        if (changed
                || forceAnnounce
                || !collectorStatusAnnounced) {
            collectorStatusAnnounced =
                    true;

            enqueueCollectorStatusForPeers(
                    collector);
        }
    }

    private void enqueueCollectorStatusForPeers(
            boolean collector) {
        int queued =
                0;

        for (PeerTrustStore.Peer peer :
                PeerTrustStore.load(
                        this)) {
            try {
                PeerCollectorStatusPayload payload =
                        PeerCollectorStatusPayload.create(
                                collector);

                PeerOutboxStore.enqueueCollectorStatus(
                        this,
                        peer.deviceId,
                        payload);

                queued++;
            } catch (RuntimeException exception) {
                EventLog.warning(
                        this,
                        getString(
                                R.string.log_peer_transport_error,
                                exception.getClass()
                                        .getSimpleName()));
            }
        }

        if (queued > 0) {
            schedulePeerSync(
                    100L);
        }
    }

    private void broadcastMeasurementClosed(
            String measurementId) {
        if (measurementId == null
                || measurementId.isBlank()) {
            return;
        }

        int queued =
                0;

        for (PeerTrustStore.Peer peer :
                PeerTrustStore.load(
                        this)) {
            try {
                PeerMeasurementClosedPayload payload =
                        PeerMeasurementClosedPayload.create(
                                measurementId);

                PeerOutboxStore.enqueueClosed(
                        this,
                        peer.deviceId,
                        payload);

                queued++;
            } catch (RuntimeException exception) {
                EventLog.warning(
                        this,
                        getString(
                                R.string.log_peer_transport_error,
                                exception.getClass()
                                        .getSimpleName()));
            }
        }

        if (queued > 0) {
            EventLog.debug(
                    this,
                    getString(
                            R.string.log_measurement_closed_queued,
                            measurementId,
                            queued));

            schedulePeerSync(
                    100L);
        }
    }

    private void enqueueManualRescueRequests(
            S400FinalMeasurement measurement,
            List<String> candidateProfileIds) {
        if (measurement == null
                || candidateProfileIds == null
                || candidateProfileIds.isEmpty()) {
            return;
        }

        SharedPreferences prefs =
                getSharedPreferences(
                        "prefs",
                        MODE_PRIVATE);

        String scaleMac =
                prefs.getString(
                        "mac",
                        "");

        if (!S400GattProtocol.isValidMacAddress(
                scaleMac)) {
            return;
        }

        int queued =
                0;

        for (PeerTrustStore.Peer peer :
                PeerTrustStore.load(
                        this)) {
            List<String> peerCandidateProfileIds =
                    new java.util.ArrayList<>();

            for (String profileId :
                    candidateProfileIds) {
                HouseholdProfile profile =
                        HouseholdProfileStore.find(
                                this,
                                profileId);

                if (profile != null
                        && peer.deviceId.equals(
                                profile.ownerDeviceId)) {
                    peerCandidateProfileIds.add(
                            profileId);
                }
            }

            if (peerCandidateProfileIds.isEmpty()) {
                continue;
            }

            try {
                PeerMeasurementPayload payload =
                        PeerMeasurementPayload.forManualRescue(
                                scaleMac,
                                measurement,
                                peerCandidateProfileIds);

                PeerOutboxStore.enqueueMeasurement(
                        this,
                        peer.deviceId,
                        payload);

                EventLog.debug(
                        this,
                        getString(
                                R.string.log_peer_claim_request_queued,
                                measurement.measurementId,
                                peer.label,
                                peerCandidateProfileIds.size()));

                queued++;
            } catch (RuntimeException exception) {
                EventLog.warning(
                        this,
                        getString(
                                R.string.log_peer_transport_error,
                                exception.getClass()
                                        .getSimpleName()));
            }
        }

        if (queued > 0) {
            schedulePeerSync(
                    100L);
        }
    }

    private void enqueueHouseholdClaimRequests(
            S400FinalMeasurement measurement,
            HouseholdMeasurementRouter.Result householdMatch) {
        if (measurement == null
                || householdMatch == null
                || householdMatch.status
                != HouseholdMeasurementRouter.Status.AMBIGUOUS) {
            return;
        }

        SharedPreferences prefs =
                getSharedPreferences(
                        "prefs",
                        MODE_PRIVATE);

        String scaleMac =
                prefs.getString(
                        "mac",
                        "");

        if (!S400GattProtocol.isValidMacAddress(
                scaleMac)) {
            return;
        }

        String localDeviceId =
                PeerTrustStore.localDeviceId(
                        this);

        for (String targetDeviceId :
                householdMatch.targetDeviceIds) {
            if (localDeviceId.equals(
                    targetDeviceId)) {
                continue;
            }

            PeerTrustStore.Peer peer =
                    PeerTrustStore.find(
                            this,
                            targetDeviceId);

            if (peer == null) {
                continue;
            }

            List<String> candidateProfileIds =
                    householdMatch.profileIdsForDevice(
                            targetDeviceId);

            if (candidateProfileIds.isEmpty()) {
                continue;
            }

            try {
                PeerMeasurementPayload payload =
                        PeerMeasurementPayload.forClaim(
                                scaleMac,
                                measurement,
                                candidateProfileIds);

                PeerOutboxStore.enqueueMeasurement(
                        this,
                        targetDeviceId,
                        payload);

                EventLog.debug(
                        this,
                        getString(
                                R.string.log_peer_claim_request_queued,
                                measurement.measurementId,
                                peer.label,
                                candidateProfileIds.size()));
            } catch (RuntimeException exception) {
                EventLog.warning(
                        this,
                        getString(
                                R.string.log_peer_transport_error,
                                exception.getClass()
                                        .getSimpleName()));
            }
        }

        schedulePeerSync(
                100L);
    }

    private void queuePeerAck(
            PeerTrustStore.Peer peer,
            String acknowledgedMessageId) {
        if (peer == null
                || acknowledgedMessageId == null
                || acknowledgedMessageId.isBlank()) {
            return;
        }

        PeerAckPayload ack =
                PeerAckPayload.create(
                        acknowledgedMessageId);

        if (peerTransport != null
                && peerTransport.sendReply(
                        peer,
                        ack.messageId,
                        ack.encode())) {
            EventLog.debug(
                    this,
                    getString(
                            R.string.log_peer_ack_session_started,
                            peer.label));
            return;
        }

        if (!ENABLE_REVERSE_ACK_FALLBACK) {
            EventLog.warning(
                    this,
                    getString(
                            R.string.log_peer_ack_reverse_fallback_disabled,
                            peer.label));
            return;
        }

        /*
         * Legacy reverse-connect fallback. Kept intact so it can be
         * re-enabled immediately if the session reply proves unreliable.
         */
        peerDirectQueue.add(
                new DirectPeerMessage(
                        peer.deviceId,
                        ack.messageId,
                        ack.encode()));

        schedulePeerSync(
                100L);
    }

    private long peerErrorRetryDelayMs() {
        String localDeviceId =
                PeerTrustStore.localDeviceId(
                        this);

        int hash =
                localDeviceId == null
                        ? 0
                        : localDeviceId.hashCode();

        long offset =
                Math.floorMod(
                        hash,
                        (int) PEER_SYNC_ERROR_RETRY_SPAN_MS);

        return PEER_SYNC_ERROR_RETRY_BASE_MS
                + offset;
    }

    private void schedulePeerSync(
            long delayMs) {
        if (explicitStop) {
            return;
        }

        handler.removeCallbacks(
                peerSyncRunnable);

        handler.postDelayed(
                peerSyncRunnable,
                Math.max(
                        0L,
                        delayMs));
    }

    private int peerOutboxPriority(
            PeerOutboxStore.Item item) {
        if (item == null) {
            return 100;
        }

        if (PeerOutboxStore.KIND_DECISION.equals(
                item.kind)) {
            return 0;
        }

        if (PeerOutboxStore.KIND_MEASUREMENT.equals(
                    item.kind)
                && item.messageId.startsWith(
                        "route:")) {
            return 1;
        }

        if (PeerOutboxStore.KIND_CLOSED.equals(
                item.kind)) {
            return 2;
        }

        if (PeerOutboxStore.KIND_CLAIM.equals(
                item.kind)) {
            return 3;
        }

        if (PeerOutboxStore.KIND_MEASUREMENT.equals(
                item.kind)) {
            return 4;
        }

        if (PeerOutboxStore.KIND_PROFILE.equals(
                item.kind)) {
            return 5;
        }

        if (PeerOutboxStore.KIND_COLLECTOR_STATUS.equals(
                item.kind)) {
            return 6;
        }

        return 50;
    }

    private void dispatchPeerOutbox() {
        if (explicitStop
                || peerTransport == null) {
            return;
        }

        if (peerSendInFlight) {
            schedulePeerSync(
                    PEER_SYNC_RETRY_MS);
            return;
        }

        while (!peerDirectQueue.isEmpty()) {
            DirectPeerMessage direct =
                    peerDirectQueue.peek();

            PeerTrustStore.Peer peer =
                    PeerTrustStore.find(
                            this,
                            direct.peerDeviceId);

            if (peer == null) {
                peerDirectQueue.poll();
                continue;
            }

            if (peerTransport.send(
                    peer,
                    direct.messageId,
                    direct.payload)) {
                peerSendInFlight =
                        true;
                return;
            }

            schedulePeerSync(
                    PEER_SYNC_RETRY_MS);
            return;
        }

        List<PeerOutboxStore.Item> items =
                PeerOutboxStore.load(
                        this);

        items.sort(
                (left, right) -> {
                    int priority =
                            Integer.compare(
                                    peerOutboxPriority(
                                            left),
                                    peerOutboxPriority(
                                            right));

                    if (priority != 0) {
                        return priority;
                    }

                    return Long.compare(
                            left.createdAtMs,
                            right.createdAtMs);
                });

        for (PeerOutboxStore.Item item :
                items) {
            PeerTrustStore.Peer peer =
                    PeerTrustStore.find(
                            this,
                            item.peerDeviceId);

            if (peer == null) {
                PeerOutboxStore.removePeer(
                        this,
                        item.peerDeviceId);
                continue;
            }

            if (peerTransport.send(
                    peer,
                    item.messageId,
                    item.payload)) {
                peerSendInFlight =
                        true;
                return;
            }

            schedulePeerSync(
                    PEER_SYNC_RETRY_MS);
            return;
        }

        schedulePeerSync(
                PEER_SYNC_RETRY_MS);
    }

    private static final class DirectPeerMessage {
        final String peerDeviceId;
        final String messageId;
        final String payload;

        DirectPeerMessage(
                String peerDeviceId,
                String messageId,
                String payload) {
            this.peerDeviceId =
                    peerDeviceId;
            this.messageId =
                    messageId;
            this.payload =
                    payload;
        }
    }

    private void scheduleGattReconnect(String reason) {
        if (!gattMonitoringActive
                || explicitStop
                || terminalError
                || gattReconnectScheduled) {
            return;
        }

        long shift = Math.min(gattReconnectAttempt, 4);
        long delayMs = Math.min(
                GATT_RECONNECT_MAX_MS,
                GATT_RECONNECT_BASE_MS << shift);

        gattReconnectAttempt++;
        gattReconnectScheduled = true;
        setCollectorOwned(
                false,
                false);

        S400GattClient oldClient = gattClient;
        gattClient = null;

        monitorText = getString(
                R.string.service_gatt_reconnecting,
                delayMs / 1000L);
        ServiceState.running(
                this,
                monitorText,
                false,
                collectorSource());
        notifyMonitor();

        EventLog.debug(
                this,
                getString(
                        R.string.log_gatt_reconnect,
                        reason,
                        delayMs / 1000L));

        handler.postDelayed(
                gattReconnectRunnable,
                delayMs);

        if (oldClient != null
                && oldClient.getState()
                != S400GattClient.State.DISCONNECTED) {
            oldClient.disconnect();
        }
    }

    private void stopGattCollector() {
        gattMonitoringActive = false;
        gattCollectorOwned = false;
        gattReconnectScheduled = false;
        gattReconnectAttempt = 0;
        handler.removeCallbacks(gattReconnectRunnable);

        S400GattClient oldClient = gattClient;
        gattClient = null;

        if (oldClient != null) {
            oldClient.disconnect();
        }
    }

    private void synchronizeOpenScaleUsers() {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String authority = prefs.getString("openscale_authority", "");
        if (authority == null || authority.isBlank()) return;

        try {
            int previousCount = UserProfileStore.load(prefs).size();
            List<OpenScaleProvider.User> currentUsers =
                    OpenScaleProvider.loadUsers(this, authority);
            List<UserProfile> synchronizedProfiles =
                    UserProfileStore.synchronize(
                            prefs,
                            currentUsers,
                            PeerTrustStore.localDeviceId(this));

            HouseholdProfileSync.pruneStaleLocalProfiles(
                    this,
                    prefs);

            int queuedProfiles =
                    0;

            for (PeerTrustStore.Peer peer :
                    PeerTrustStore.load(this)) {
                queuedProfiles +=
                        HouseholdProfileSync.enqueueAllProfilesForPeer(
                                this,
                                prefs,
                                peer.deviceId);
            }

            if (queuedProfiles > 0) {
                schedulePeerSync(
                        100L);
            }

            if (previousCount != synchronizedProfiles.size()) {
                EventLog.info(this, getString(
                        R.string.log_openscale_users_synced,
                        synchronizedProfiles.size()));
            }
        } catch (SecurityException e) {
            EventLog.debug(
                    this,
                    getString(R.string.log_user_sync_permission_missing));
        } catch (RuntimeException e) {
            EventLog.debug(
                    this,
                    getString(R.string.log_user_sync_temporary_failure));
        }
    }

    private void repairStaleAmbiguousPending() {
        SharedPreferences prefs =
                getSharedPreferences(
                        "prefs",
                        MODE_PRIVATE);

        List<PendingMeasurementStore.Item> snapshot =
                new java.util.ArrayList<>(
                        PendingMeasurementStore.load(
                                prefs));

        for (PendingMeasurementStore.Item pending :
                snapshot) {
            if (pending == null
                    || pending.isResolved()
                    || pending.manualRescue) {
                continue;
            }

            boolean hasEmptyRemoteClaim =
                    false;

            for (PendingMeasurementStore.ClaimResponse response :
                    PendingMeasurementStore.claimResponses(
                            prefs,
                            pending.id)) {
                if (response.profileIds.isEmpty()) {
                    hasEmptyRemoteClaim =
                            true;
                    break;
                }
            }

            if (!hasEmptyRemoteClaim) {
                continue;
            }

            List<UserProfile> localProfiles =
                    UserProfileStore.enabled(
                            UserProfileStore.load(
                                    prefs));

            UserMatcher.Result localMatch =
                    UserMatcher.match(
                            localProfiles,
                            pending.weightKg);

            if (localMatch.status
                    != UserMatcher.Status.NO_MATCH) {
                continue;
            }

            List<String> candidateProfileIds =
                    new java.util.ArrayList<>();

            for (HouseholdProfile profile :
                    HouseholdProfileStore.active(
                            this)) {
                if (profile != null
                        && UserProfile.isValidHouseholdProfileId(
                                profile.profileId)
                        && !candidateProfileIds.contains(
                                profile.profileId)) {
                    candidateProfileIds.add(
                            profile.profileId);
                }
            }

            for (UserProfile profile :
                    localProfiles) {
                if (profile != null
                        && UserProfile.isValidHouseholdProfileId(
                                profile.householdProfileId)
                        && !candidateProfileIds.contains(
                                profile.householdProfileId)) {
                    candidateProfileIds.add(
                            profile.householdProfileId);
                }
            }

            if (candidateProfileIds.isEmpty()) {
                continue;
            }

            /*
             * Remove the obsolete normal CLAIM from the outbox before
             * queuing CLOSED + the new rescue request.
             */
            PeerOutboxStore.removeMeasurement(
                    this,
                    pending.id);

            broadcastMeasurementClosed(
                    pending.id);

            PendingMeasurementStore.remove(
                    prefs,
                    pending.id);

            String reason =
                    getString(
                            R.string.pending_reason_no_weight_match);

            PendingMeasurementStore.Item repaired =
                    PendingMeasurementStore.add(
                            prefs,
                            pending.toMeasurement(),
                            reason,
                            candidateProfileIds,
                            true);

            EventLog.warning(
                    this,
                    getString(
                            R.string.log_measurement_unassigned,
                            repaired.weightKg,
                            reason));

            EventLog.debug(
                    this,
                    getString(
                            R.string.log_pending_measurement_saved,
                            repaired.id));

            enqueueManualRescueRequests(
                    repaired.toMeasurement(),
                    candidateProfileIds);
        }
    }

    private void routeMeasurement(S400FinalMeasurement measurement) {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);

        List<UserProfile> profiles =
                UserProfileStore.enabled(
                        UserProfileStore.load(
                                prefs));

        UserMatcher.Result match =
                UserMatcher.match(
                        profiles,
                        measurement.weightKg);

        EventLog.debug(
                this,
                getString(
                        R.string.log_user_match,
                        UserMatcher.diagnosticSummary(
                                this,
                                match)));

        HouseholdMeasurementRouter.Result householdMatch =
                HouseholdMeasurementRouter.match(
                        HouseholdProfileStore.active(
                                this),
                        measurement.weightKg);

        List<String> householdCandidateProfileIds =
                new java.util.ArrayList<>();

        for (HouseholdMeasurementRouter.Candidate candidate :
                householdMatch.candidates) {
            if (candidate != null
                    && candidate.profile != null
                    && UserProfile.isValidHouseholdProfileId(
                            candidate.profile.profileId)) {
                householdCandidateProfileIds.add(
                        candidate.profile.profileId);
            }
        }

        EventLog.debug(
                this,
                getString(
                        R.string.log_household_match,
                        householdMatch.status.name(),
                        householdCandidateProfileIds.size()));

        for (HouseholdMeasurementRouter.Candidate candidate :
                householdMatch.candidates) {
            if (candidate == null
                    || candidate.profile == null) {
                continue;
            }

            EventLog.debug(
                    this,
                    getString(
                            R.string.log_household_candidate,
                            candidate.profile.name,
                            candidate.profile.ownerDeviceId,
                            candidate.profile.profileId,
                            candidate.profile.referenceWeightKg,
                            candidate.profile.toleranceKg,
                            candidate.differenceKg));
        }

        if (match.status
                == UserMatcher.Status.NO_MATCH
                && householdMatch.status
                == HouseholdMeasurementRouter.Status.UNIQUE
                && householdMatch.uniqueProfile != null) {
            HouseholdProfile target =
                    householdMatch.uniqueProfile;

            String localDeviceId =
                    PeerTrustStore.localDeviceId(
                            this);

            if (!localDeviceId.equals(
                        target.ownerDeviceId)
                    && PeerTrustStore.find(
                            this,
                            target.ownerDeviceId) != null) {
                PendingMeasurementStore.Item pending =
                        PendingMeasurementStore.add(
                                prefs,
                                measurement,
                                getString(
                                        R.string.pending_reason_no_weight_match),
                                List.of(
                                        target.profileId));

                if (PendingMeasurementStore.selectCandidate(
                        prefs,
                        pending.id,
                        target.profileId,
                        target.ownerDeviceId)) {
                    resolvePendingDecision(
                            prefs,
                            pending.id);
                    return;
                }

                PendingMeasurementStore.remove(
                        prefs,
                        pending.id);
            }
        }

        if (MeasurementRoutingPolicy.shouldCreateHouseholdAmbiguousPending(
                match.status,
                householdMatch.status)) {
            String reason =
                    getString(
                            R.string.pending_reason_similar_users);

            PendingMeasurementStore.Item pending =
                    PendingMeasurementStore.add(
                            prefs,
                            measurement,
                            reason,
                            householdCandidateProfileIds);

            EventLog.warning(
                    this,
                    getString(
                            R.string.log_measurement_unassigned,
                            measurement.weightKg,
                            reason));

            EventLog.debug(
                    this,
                    getString(
                            R.string.log_pending_measurement_saved,
                            pending.id));

            updateMonitor(
                    getString(
                            R.string.service_user_assignment_required));

            updateAssignmentNotification();

            /*
             * Persist the pending measurement before sending CLAIM requests.
             * A fast peer response must never arrive before the collector has
             * a pending measurement against which the claim can be validated.
             */
            enqueueHouseholdClaimRequests(
                    measurement,
                    householdMatch);

            return;
        }

        if (match.status == UserMatcher.Status.MATCHED && match.profile != null) {
            EventLog.info(this, getString(
                    R.string.log_measurement_auto_assigned,
                    measurement.weightKg,
                    match.profile.name));
            updateMonitor(getString(
                    R.string.service_measurement_detected_for,
                    match.profile.name));
            processMeasurement(measurement, match.profile);
            return;
        }

        String reason = getString(
                match.status == UserMatcher.Status.AMBIGUOUS
                        ? R.string.pending_reason_similar_users
                        : R.string.pending_reason_no_weight_match);

        List<String> pendingCandidateProfileIds =
                new java.util.ArrayList<>(
                        householdCandidateProfileIds);

        if (match.status
                == UserMatcher.Status.NO_MATCH) {
            for (HouseholdProfile profile :
                    HouseholdProfileStore.active(
                            this)) {
                if (UserProfile.isValidHouseholdProfileId(
                        profile.profileId)) {
                    pendingCandidateProfileIds.add(
                            profile.profileId);
                }
            }
        }

        PendingMeasurementStore.Item pending = PendingMeasurementStore.add(
                prefs,
                measurement,
                reason,
                pendingCandidateProfileIds,
                match.status == UserMatcher.Status.NO_MATCH);
        EventLog.warning(this, getString(
                R.string.log_measurement_unassigned,
                measurement.weightKg,
                reason));
        EventLog.debug(this, getString(R.string.log_pending_measurement_saved, pending.id));
        updateMonitor(getString(R.string.service_user_assignment_required));
        updateAssignmentNotification();

        if (match.status
                == UserMatcher.Status.NO_MATCH) {
            enqueueManualRescueRequests(
                    measurement,
                    pendingCandidateProfileIds);
        }
    }

    private void selectPendingCandidate(
            String pendingId,
            String profileId,
            String ownerDeviceId) {
        if (pendingId == null
                || pendingId.isBlank()
                || !UserProfile.isValidHouseholdProfileId(
                        profileId)
                || !PeerTrustStore.isValidDeviceId(
                        ownerDeviceId)) {
            return;
        }

        SharedPreferences prefs =
                getSharedPreferences(
                        "prefs",
                        MODE_PRIVATE);

        PendingMeasurementStore.Item pending =
                PendingMeasurementStore.find(
                        prefs,
                        pendingId);

        String localDeviceId =
                PeerTrustStore.localDeviceId(
                        this);

        if (!localDeviceId.equals(
                    ownerDeviceId)
                || pending == null
                || !validSelectablePendingCandidate(
                        prefs,
                        pending,
                        profileId,
                        ownerDeviceId)) {
            EventLog.warning(
                    this,
                    getString(
                            R.string.log_pending_candidate_invalid,
                            pendingId));
            updateAssignmentNotification();
            return;
        }

        if (!PendingMeasurementStore.selectCandidate(
                prefs,
                pendingId,
                profileId,
                ownerDeviceId)) {
            EventLog.debug(
                    this,
                    getString(
                            R.string.log_pending_candidate_already_decided,
                            pendingId));
            updateAssignmentNotification();
            return;
        }

        EventLog.info(
                this,
                getString(
                        R.string.log_pending_candidate_selected,
                        pending.weightKg,
                        pendingDisplayName(
                                profileId)));

        resolvePendingDecision(
                prefs,
                pendingId);
    }

    private void rejectLocalPendingCandidates(
            String pendingId) {
        if (pendingId == null
                || pendingId.isBlank()) {
            return;
        }

        SharedPreferences prefs =
                getSharedPreferences(
                        "prefs",
                        MODE_PRIVATE);

        PendingMeasurementStore.Item pending =
                PendingMeasurementStore.find(
                        prefs,
                        pendingId);

        if (pending == null
                || pending.isResolved()) {
            return;
        }

        String localDeviceId =
                PeerTrustStore.localDeviceId(
                        this);

        int rejectedCount =
                0;

        List<String> remaining =
                new java.util.ArrayList<>(
                        pending.remainingCandidateProfileIds());

        for (String profileId :
                remaining) {
            HouseholdProfile household =
                    HouseholdProfileStore.find(
                            this,
                            profileId);

            if (household == null
                    || !localDeviceId.equals(
                            household.ownerDeviceId)) {
                continue;
            }

            if (PendingMeasurementStore.rejectCandidate(
                    prefs,
                    pendingId,
                    profileId)) {
                rejectedCount++;
            }
        }

        if (rejectedCount > 0) {
            EventLog.info(
                    this,
                    getString(
                            R.string.log_pending_local_candidates_rejected,
                            pending.weightKg,
                            rejectedCount));
        }

        autoResolveSingleRemainingCandidate(
                prefs,
                pendingId);

        boolean remoteRescueStarted =
                rejectedCount > 0
                        && promoteRejectedLocalPendingToRemoteRescue(
                                prefs,
                                pendingId);

        if (!remoteRescueStarted) {
            removePendingWithoutCandidates(
                    prefs,
                    pendingId);
        }

        updateAssignmentNotification();
    }

    private boolean promoteRejectedLocalPendingToRemoteRescue(
            SharedPreferences prefs,
            String pendingId) {
        PendingMeasurementStore.Item pending =
                PendingMeasurementStore.find(
                        prefs,
                        pendingId);

        if (pending == null
                || pending.isResolved()
                || pending.manualRescue
                || !pending.remainingCandidateProfileIds().isEmpty()) {
            return false;
        }

        String localDeviceId =
                PeerTrustStore.localDeviceId(
                        this);

        List<String> rescueCandidateProfileIds =
                new java.util.ArrayList<>();

        /*
         * Preserve the rejected local candidates so the collector continues
         * to remember that all local users were explicitly excluded.
         */
        for (String profileId :
                pending.candidateProfileIds) {
            HouseholdProfile profile =
                    HouseholdProfileStore.find(
                            this,
                            profileId);

            if (profile != null
                    && localDeviceId.equals(
                            profile.ownerDeviceId)
                    && !rescueCandidateProfileIds.contains(
                            profileId)) {
                rescueCandidateProfileIds.add(
                        profileId);
            }
        }

        boolean hasRemoteCandidate =
                false;

        /*
         * After all local users were rejected, remote household users may
         * claim the measurement manually even when they were outside their
         * automatic weight tolerance. A remote user who already rejected the
         * measurement must not be asked again.
         */
        for (HouseholdProfile profile :
                HouseholdProfileStore.active(
                        this)) {
            if (profile == null
                    || !UserProfile.isValidHouseholdProfileId(
                            profile.profileId)
                    || localDeviceId.equals(
                            profile.ownerDeviceId)
                    || pending.rejectedProfileIds.contains(
                            profile.profileId)
                    || PeerTrustStore.find(
                            this,
                            profile.ownerDeviceId) == null) {
                continue;
            }

            if (!rescueCandidateProfileIds.contains(
                    profile.profileId)) {
                rescueCandidateProfileIds.add(
                        profile.profileId);
            }

            hasRemoteCandidate =
                    true;
        }

        if (!hasRemoteCandidate) {
            return false;
        }

        S400FinalMeasurement measurement =
                pending.toMeasurement();

        PeerOutboxStore.removeMeasurement(
                this,
                pending.id);

        broadcastMeasurementClosed(
                pending.id);

        PendingMeasurementStore.remove(
                prefs,
                pending.id);

        String reason =
                getString(
                        R.string.pending_reason_no_weight_match);

        PendingMeasurementStore.Item rescue =
                PendingMeasurementStore.add(
                        prefs,
                        measurement,
                        reason,
                        rescueCandidateProfileIds,
                        true);

        for (String profileId :
                new java.util.ArrayList<>(
                        rescue.candidateProfileIds)) {
            HouseholdProfile profile =
                    HouseholdProfileStore.find(
                            this,
                            profileId);

            if (profile != null
                    && localDeviceId.equals(
                            profile.ownerDeviceId)) {
                PendingMeasurementStore.rejectCandidate(
                        prefs,
                        rescue.id,
                        profileId);
            }
        }

        EventLog.debug(
                this,
                getString(
                        R.string.log_pending_measurement_saved,
                        rescue.id));

        enqueueManualRescueRequests(
                rescue.toMeasurement(),
                rescue.remainingCandidateProfileIds());

        return true;
    }

    private boolean validSelectablePendingCandidate(
            SharedPreferences prefs,
            PendingMeasurementStore.Item pending,
            String profileId,
            String ownerDeviceId) {
        if (prefs == null
                || pending == null
                || pending.isResolved()
                || !pending.remainingCandidateProfileIds()
                        .contains(
                                profileId)) {
            return false;
        }

        HouseholdProfile householdProfile =
                null;

        for (HouseholdProfile profile :
                HouseholdProfileStore.active(
                        this)) {
            if (profileId.equals(
                        profile.profileId)
                    && ownerDeviceId.equals(
                            profile.ownerDeviceId)) {
                householdProfile =
                        profile;
                break;
            }
        }

        if (householdProfile == null) {
            return false;
        }

        String localDeviceId =
                PeerTrustStore.localDeviceId(
                        this);

        if (localDeviceId.equals(
                ownerDeviceId)) {
            UserProfile local =
                    UserProfileStore.findByHouseholdProfileId(
                            UserProfileStore.enabled(
                                    UserProfileStore.load(
                                            prefs)),
                            profileId);

            return local != null
                    && local.hasValidBodyData(
                            pending.timestampMs);
        }

        if (PeerTrustStore.find(
                this,
                ownerDeviceId) == null) {
            return false;
        }

        for (PendingMeasurementStore.ClaimResponse response :
                PendingMeasurementStore.claimResponses(
                        prefs,
                        pending.id)) {
            if (ownerDeviceId.equals(
                        response.peerDeviceId)
                    && response.profileIds.contains(
                            profileId)) {
                return true;
            }
        }

        return false;
    }

    private void autoResolveSingleRemainingCandidate(
            SharedPreferences prefs,
            String pendingId) {
        PendingMeasurementStore.Item pending =
                PendingMeasurementStore.find(
                        prefs,
                        pendingId);

        if (pending == null
                || pending.isResolved()) {
            return;
        }

        List<String> remaining =
                pending.remainingCandidateProfileIds();

        if (!MeasurementRoutingPolicy
                .shouldAutoResolveSingleRemainingCandidate(
                        pending.manualRescue,
                        remaining.size())) {
            return;
        }

        String profileId =
                remaining.get(0);

        for (HouseholdProfile profile :
                HouseholdProfileStore.active(
                        this)) {
            if (!profileId.equals(
                    profile.profileId)) {
                continue;
            }

            if (!validSelectablePendingCandidate(
                    prefs,
                    pending,
                    profileId,
                    profile.ownerDeviceId)) {
                return;
            }

            if (PendingMeasurementStore.selectCandidate(
                    prefs,
                    pendingId,
                    profileId,
                    profile.ownerDeviceId)) {
                EventLog.info(
                        this,
                        getString(
                                R.string.log_pending_single_candidate,
                                pending.weightKg,
                                profile.name));

                resolvePendingDecision(
                        prefs,
                        pendingId);
            }

            return;
        }
    }

    private void resolvePendingDecision(
            SharedPreferences prefs,
            String pendingId) {
        PendingMeasurementStore.Item pending =
                PendingMeasurementStore.find(
                        prefs,
                        pendingId);

        if (pending == null
                || !pending.isResolved()) {
            return;
        }

        String localDeviceId =
                PeerTrustStore.localDeviceId(
                        this);

        if (localDeviceId.equals(
                pending.selectedOwnerDeviceId)) {
            UserProfile target =
                    UserProfileStore.findByHouseholdProfileId(
                            UserProfileStore.enabled(
                                    UserProfileStore.load(
                                            prefs)),
                            pending.selectedProfileId);

            if (target == null
                    || !target.hasValidBodyData(
                            pending.timestampMs)) {
                EventLog.error(
                        this,
                        getString(
                                R.string.service_error_selected_profile));
                return;
            }

            EventLog.info(
                    this,
                    getString(
                            R.string.log_measurement_manually_assigned,
                            pending.weightKg,
                            target.name));

            if (processMeasurement(
                    pending.toMeasurement(),
                    target)) {
                PeerOutboxStore.removeMeasurement(
                        this,
                        pending.id);

                broadcastMeasurementClosed(
                        pending.id);

                PendingMeasurementStore.remove(
                        prefs,
                        pending.id);

                updateAssignmentNotification();
            }

            return;
        }

        if (enqueueRoutedMeasurement(
                pending,
                pending.selectedProfileId,
                pending.selectedOwnerDeviceId)) {
            broadcastMeasurementClosed(
                    pending.id);

            updateAssignmentNotification();
        }
    }

    private String pendingDisplayName(
            String profileId) {
        for (HouseholdProfile profile :
                HouseholdProfileStore.active(
                        this)) {
            if (profileId.equals(
                    profile.profileId)) {
                return profile.name;
            }
        }

        return profileId;
    }

    private void assignPending(String pendingId, long userId) {
        if (pendingId == null || pendingId.isBlank() || userId < 0L) return;
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        PendingMeasurementStore.Item pending = PendingMeasurementStore.find(prefs, pendingId);
        UserProfile profile = UserProfileStore.find(UserProfileStore.load(prefs), userId);
        if (pending == null) {
            EventLog.warning(this, getString(R.string.log_pending_measurement_missing));
            updateAssignmentNotification();
            return;
        }
        if (profile == null || !profile.enabled || !profile.hasValidBodyData(pending.timestampMs)) {
            EventLog.error(this, getString(R.string.service_error_selected_profile));
            return;
        }

        EventLog.info(this, getString(
                R.string.log_measurement_manually_assigned,
                pending.weightKg,
                profile.name));
        if (processMeasurement(pending.toMeasurement(), profile)) {
            PeerOutboxStore.removeMeasurement(
                    this,
                    pending.id);

            broadcastMeasurementClosed(
                    pending.id);

            PendingMeasurementStore.remove(
                    prefs,
                    pending.id);

            updateAssignmentNotification();
        }
    }

    private boolean processMeasurement(S400FinalMeasurement measurement,
                                       UserProfile profile) {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String authority = prefs.getString("openscale_authority", "");
        LocalDate birthDate = BirthDateUtils.parseIso(profile.birthDateIso);
        long timestamp = measurement.timestampMs > 0L
                ? measurement.timestampMs
                : System.currentTimeMillis();
        int age = BirthDateUtils.ageOn(birthDate, timestamp);

        if (age < 18 || age > 120) {
            rejectMeasurement(getString(
                    R.string.service_error_invalid_birth_date,
                    profile.name));
            return false;
        }

        if (!measurement.isComplete() || measurement.impedanceLow == null) {
            rejectMeasurement(getString(R.string.service_error_measurement_incomplete));
            return false;
        }
        S400BodyComposition.Result composition = S400BodyComposition.compute(
                new S400BodyComposition.Inputs(
                        age,
                        profile.male,
                        profile.heightCm,
                        measurement.weightKg,
                        measurement.impedanceHigh,
                        measurement.impedanceLow));

        String compositionError = validateCompleteComposition(composition);
        if (compositionError != null) {
            rejectMeasurement(getString(
                    R.string.service_error_incomplete_composition,
                    compositionError));
            return false;
        }
        if (composition.impedanceLabelsSwapped) {
            EventLog.debug(this, getString(R.string.log_impedance_labels_swapped));
        }
        EventLog.debug(this, buildCalculationLog(profile.name, age, measurement, composition));

        boolean openScaleStored;
        try {
            OpenScaleProvider.Meta meta = OpenScaleProvider.readMeta(this, authority);
            if (!meta.supportsGenericValues()) {
                rejectMeasurement(getString(R.string.service_error_provider_api));
                return false;
            }
            prefs.edit().putInt("openscale_api_version", meta.apiVersion).apply();
            OpenScaleProvider.InsertResult result = OpenScaleProvider.insertMeasurement(
                    this,
                    authority,
                    profile.userId,
                    timestamp,
                    meta.apiVersion,
                    measurement,
                    composition);
            openScaleStored = logProviderResult(result, profile.name);
            if (openScaleStored) {
                boolean referenceUpdated =
                        HouseholdProfileSync.updateReferenceWeight(
                                this,
                                prefs,
                                profile.userId,
                                measurement.weightKg);

                if (referenceUpdated) {
                    profile.referenceWeightKg =
                            measurement.weightKg;

                    EventLog.debug(this, getString(
                            R.string.log_reference_weight_updated,
                            profile.name,
                            measurement.weightKg));

                    schedulePeerSync(
                            100L);
                }
            }
        } catch (SecurityException e) {
            rejectMeasurement(
                    getString(R.string.service_error_openscale_access));
            return false;
        } catch (RuntimeException e) {
            rejectMeasurement(getString(
                    R.string.service_error_openscale_transfer,
                    e.getClass().getSimpleName(),
                    safeMessage(e)));
            return false;
        }

        if (!openScaleStored) {
            rejectMeasurement(getString(R.string.service_error_openscale_unconfirmed));
            return false;
        }

        boolean healthConnectStarted = writeToHealthConnect(
                prefs, profile, timestamp, measurement, composition);
        if (!healthConnectStarted) markMeasurementSuccess(profile.name);
        return true;
    }

    private boolean writeToHealthConnect(SharedPreferences prefs,
                                      UserProfile profile,
                                      long timestamp,
                                      S400FinalMeasurement measurement,
                                      S400BodyComposition.Result composition) {
        if (!prefs.getBoolean("health_connect_enabled", false)) return false;
        long healthUserId = prefs.getLong("health_connect_user_id", -1L);
        if (profile.userId != healthUserId) {
            EventLog.debug(this, getString(
                    R.string.log_health_connect_skipped_user,
                    profile.name));
            return false;
        }

        HealthConnectSelection selection = HealthConnectSelection.fromPreferences(prefs);
        if (selection.count() == 0) {
            EventLog.error(
                    this,
                    getString(R.string.log_health_connect_no_values));
            notifyTransferFailure(
                    getString(R.string.transfer_health_connect_misconfigured));
            updateMonitor(getString(R.string.service_health_connect_misconfigured));
            return true;
        }

        String scaleMac = prefs.getString("mac", "");
        HealthConnectWriter.write(
                this,
                timestamp,
                scaleMac,
                profile.heightCm,
                measurement,
                composition,
                selection,
                new HealthConnectWriter.Callback() {
                    @Override public void onSuccess(int writtenRecordCount, String writtenValues) {
                        EventLog.info(
                                ScaleScanService.this,
                                getString(
                                        R.string.log_health_connect_values_saved,
                                        profile.name,
                                        writtenRecordCount));
                        EventLog.debug(
                                ScaleScanService.this,
                                getString(
                                        R.string.log_health_connect_written,
                                        writtenValues));
                        markMeasurementSuccess(profile.name);
                    }

                    @Override public void onError(String message) {
                        EventLog.error(
                                ScaleScanService.this,
                                getString(
                                        R.string.log_health_connect_failed,
                                        message));
                        notifyTransferFailure(
                                getString(R.string.transfer_health_connect_permissions));
                        updateMonitor(getString(R.string.service_health_connect_failed));
                    }
                });
        return true;
    }

    private void rejectMeasurement(String reason) {
        String detail = reason == null || reason.isBlank()
                ? getString(R.string.service_error_measurement_incomplete)
                : reason;
        EventLog.error(this, getString(R.string.log_measurement_rejected, detail));
        ServiceState.measurementFailed(this);
        updateMonitor(getString(R.string.service_last_measurement_failed));
        notifyMeasurementFailure(detail);
    }

    /**
     * Returns null only when every value expected from a complete S400 body analysis
     * is finite and usable. Approximate/partial calculations are deliberately rejected.
     */
    private String validateCompleteComposition(S400BodyComposition.Result value) {
        if (value == null) {
            return getString(R.string.composition_error_no_result);
        }
        if (value.reliability != S400BodyComposition.Reliability.OK) {
            return getString(
                    R.string.composition_error_quality,
                    value.reliability.name());
        }

        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        requirePositive(missing, getString(R.string.composition_label_bmi), value.bmi);
        requirePositive(missing, getString(R.string.composition_label_body_water), value.totalBodyWaterKg);
        requirePercent(missing, getString(R.string.composition_label_body_water_percent), value.totalBodyWaterPercent);
        requirePositive(missing, getString(R.string.composition_label_ecw), value.extracellularWaterKg);
        requirePercent(missing, getString(R.string.composition_label_ecw_percent), value.extracellularWaterPercent);
        requirePositive(missing, getString(R.string.composition_label_icw), value.intracellularWaterKg);
        requirePercent(missing, getString(R.string.composition_label_icw_percent), value.intracellularWaterPercent);
        requirePositive(missing, getString(R.string.composition_label_lean_mass), value.fatFreeMassKg);
        requirePercent(missing, getString(R.string.composition_label_lean_mass_percent), value.fatFreeMassPercent);
        requirePositive(missing, getString(R.string.composition_label_body_fat), value.bodyFatKg);
        requirePercent(missing, getString(R.string.composition_label_body_fat_percent), value.bodyFatPercent);
        requirePositive(missing, getString(R.string.composition_label_muscle_mass), value.skeletalMuscleKg);
        requirePercent(missing, getString(R.string.composition_label_muscle_mass_percent), value.skeletalMusclePercent);
        requirePositive(missing, getString(R.string.composition_label_bone_mass), value.boneKg);
        requirePositive(missing, getString(R.string.composition_label_visceral_fat), value.visceralFatIndex);
        requirePositive(missing, getString(R.string.composition_label_bmr), value.basalMetabolicRateKcal);
        requirePositive(missing, getString(R.string.composition_label_body_cell_mass), value.bodyCellMassKg);
        requirePositive(missing, getString(R.string.composition_label_protein), value.proteinKg);
        requirePercent(missing, getString(R.string.composition_label_protein_percent), value.proteinPercent);
        requirePositive(missing, getString(R.string.composition_label_soft_lean_mass), value.softLeanMassKg);

        return missing.isEmpty()
                ? null
                : getString(
                        R.string.composition_error_missing,
                        String.join(", ", missing));
    }

    private static void requirePositive(List<String> missing, String name, Float value) {
        if (value == null || !Float.isFinite(value) || value <= 0f) missing.add(name);
    }

    private static void requirePositive(List<String> missing, String name, float value) {
        if (!Float.isFinite(value) || value <= 0f) missing.add(name);
    }

    private static void requirePercent(List<String> missing, String name, Float value) {
        if (value == null || !Float.isFinite(value) || value <= 0f || value > 100f) {
            missing.add(name);
        }
    }

    private void markMeasurementSuccess(String userName) {
        ServiceState.measurementSucceeded(this);
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.cancel(LEGACY_NOTIFICATION_TRANSFER_FAILURE);
        manager.notify(
                NOTIFICATION_RESULT,
                resultNotification(
                        getString(
                                R.string.notification_measurement_success_title,
                                userName),
                        getString(R.string.notification_measurement_success_text),
                        false));
        String nextMonitorText =
                gattCollectorOwned
                                && gattClient != null
                                && gattClient.isReady()
                        ? getString(
                                R.string.service_gatt_ready)
                        : gattMonitoringActive
                                ? getString(
                                        R.string.service_gatt_standby)
                                : getString(
                                        R.string.service_waiting_for_measurement);

        updateMonitor(
                nextMonitorText);
    }

    private void notifyMeasurementFailure(String reason) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.cancel(LEGACY_NOTIFICATION_TRANSFER_FAILURE);
        manager.notify(
                NOTIFICATION_RESULT,
                resultNotification(
                        getString(R.string.notification_measurement_failure_title),
                        shorten(reason),
                        true));
    }

    private void notifyTransferFailure(String reason) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(
                NOTIFICATION_RESULT,
                resultNotification(
                        getString(R.string.notification_transfer_incomplete_title),
                        shorten(reason),
                        true));
    }

    private String shorten(String value) {
        if (value == null || value.isBlank()) return getString(R.string.service_error_unknown);
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 150 ? clean : clean.substring(0, 147) + "…";
    }

    private boolean logProviderResult(OpenScaleProvider.InsertResult result, String userName) {
        if (result.measurementVerified && result.additionalValuesVerified) {
            EventLog.info(this, getString(
                    R.string.log_openscale_measurement_saved,
                    userName,
                    result.storedValueCount));
            EventLog.debug(this, getString(
                    R.string.log_provider_api_verified,
                    result.apiVersion));
            updateMonitor(getString(
                    R.string.service_measurement_saved,
                    userName));
            return true;
        }

        String missing = result.missingValueKeys == null || result.missingValueKeys.isEmpty()
                ? getString(R.string.log_openscale_unknown_values)
                : String.join(", ", result.missingValueKeys);
        EventLog.error(this, getString(
                result.rollbackPerformed
                        ? R.string.log_openscale_incomplete_deleted
                        : R.string.log_openscale_incomplete_delete_failed,
                userName,
                missing));
        return false;
    }

    private String buildCalculationLog(String userName,
                                       int age,
                                       S400FinalMeasurement measurement,
                                       S400BodyComposition.Result composition) {
        StringBuilder text = new StringBuilder(getString(
                R.string.log_s400_evaluated,
                userName,
                age,
                measurement.weightKg));

        appendValue(text, getString(R.string.composition_label_bmi), composition.bmi, "");
        appendPercent(text, getString(R.string.composition_log_fat), composition.bodyFatPercent);
        appendPercent(text, getString(R.string.composition_log_water), composition.totalBodyWaterPercent);
        appendPercent(text, getString(R.string.composition_log_muscle), composition.skeletalMusclePercent);
        appendKg(text, getString(R.string.composition_log_bone), composition.boneKg);
        appendKg(text, getString(R.string.composition_log_lbm), composition.fatFreeMassKg);
        appendValue(text, getString(R.string.composition_label_visceral_fat), composition.visceralFatIndex, "");
        appendValue(
                text,
                getString(R.string.composition_log_bmr),
                composition.basalMetabolicRateKcal,
                getString(R.string.unit_kcal_suffix));
        appendPercent(text, getString(R.string.composition_label_protein), composition.proteinPercent);
        appendPercent(text, getString(R.string.composition_label_ecw), composition.extracellularWaterPercent);
        appendPercent(text, getString(R.string.composition_label_icw), composition.intracellularWaterPercent);
        appendKg(text, getString(R.string.composition_log_bcm), composition.bodyCellMassKg);
        text.append(getString(
                R.string.log_calculation_quality,
                composition.reliability.name()));
        return text.toString();
    }

    private void appendPercent(StringBuilder text, String label, Float value) {
        if (value != null) {
            text.append(getString(R.string.log_calculation_percent, label, value));
        }
    }

    private void appendKg(StringBuilder text, String label, Float value) {
        if (value != null) {
            text.append(getString(R.string.log_calculation_kg, label, value));
        }
    }

    private void appendValue(StringBuilder text,
                             String label,
                             Float value,
                             String suffix) {
        if (value != null) {
            text.append(getString(
                    R.string.log_calculation_value,
                    label,
                    value,
                    suffix));
        }
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? getString(R.string.service_no_detail)
                : message;
    }

    private boolean isRemoteCollectorReachable() {
        return !remoteCollectorLastSeenMs.isEmpty();
    }

    private ServiceState.CollectorSource collectorSource() {
        if (gattCollectorOwned) {
            return ServiceState.CollectorSource.LOCAL;
        }

        if (isRemoteCollectorReachable()) {
            return ServiceState.CollectorSource.REMOTE;
        }

        return ServiceState.CollectorSource.NONE;
    }

    private void expireRemoteCollectorPresence() {
        ServiceState.CollectorSource previousSource =
                collectorSource();

        long now =
                SystemClock.elapsedRealtime();

        remoteCollectorLastSeenMs.entrySet()
                .removeIf(
                        entry ->
                                now - entry.getValue()
                                        >= REMOTE_COLLECTOR_REACHABLE_MS);

        ServiceState.CollectorSource currentSource =
                collectorSource();

        if (currentSource != previousSource) {
            ServiceState.heartbeat(
                    this,
                    gattCollectorOwned,
                    monitorText,
                    false,
                    currentSource);
            notifyMonitor();
        }
    }

    private void runWatchdog() {
        if (explicitStop) return;

        expireRemoteCollectorPresence();

        if (terminalError) {
            ServiceState.heartbeat(
                    this,
                    false,
                    monitorText,
                    false,
                    collectorSource());
            handler.removeCallbacks(watchdogRunnable);
            handler.postDelayed(
                    watchdogRunnable,
                    WATCHDOG_INTERVAL_MS);
            return;
        }

        if (!PowerSettingsHelper.isBatteryOptimizationDisabled(this)) {
            enterTerminalError(
                    getString(
                            R.string.service_error_battery_optimization_returned));
        } else if (!PowerSettingsHelper.isUnusedAppManagementDisabled(this)) {
            enterTerminalError(
                    getString(
                            R.string.service_error_unused_app_management_returned));
        } else if (!PowerSettingsHelper.areNotificationsUsable(this)) {
            enterTerminalError(
                    getString(
                            R.string.service_error_notifications_disabled));
        } else if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            enterTerminalError(
                    getString(
                            R.string.service_error_bluetooth_permission_revoked));
        } else {
            BluetoothManager manager =
                    (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            BluetoothAdapter adapter =
                    manager == null ? null : manager.getAdapter();

            if (adapter == null || !adapter.isEnabled()) {
                enterRecoverableError(
                        getString(
                                R.string.service_error_bluetooth_disabled));

                if (gattMonitoringActive
                        && !gattReconnectScheduled) {
                    scheduleGattReconnect(
                            getString(
                                    R.string.service_error_bluetooth_disabled));
                }
            } else if (!gattMonitoringActive) {
                startGattCollector();
            } else if (gattClient == null) {
                ServiceState.heartbeat(
                        this,
                        false,
                        monitorText,
                        false,
                        collectorSource());

                if (!gattReconnectScheduled) {
                    connectGattCollector();
                }
            } else {
                boolean ready =
                        gattCollectorOwned && gattClient.isReady();
                ServiceState.heartbeat(
                        this,
                        ready,
                        monitorText,
                        ready,
                        collectorSource());
            }

            if (!terminalError
                    && adapter != null
                    && adapter.isEnabled()
                    && peerTransport != null) {
                peerTransport.ensurePresenceScan();
            }
        }

        handler.removeCallbacks(watchdogRunnable);
        handler.postDelayed(
                watchdogRunnable,
                WATCHDOG_INTERVAL_MS);
    }

    private void enterTerminalError(String reason) {
        terminalError = true;
        setCollectorOwned(
                false,
                false);
        stopGattCollector();
        monitorText = reason;
        ServiceState.error(this, reason);
        EventLog.error(this, getString(R.string.log_monitor_stopped, reason));
        notifyMonitor();
    }

    private void enterRecoverableError(String reason) {
        terminalError = false;
        monitorText = reason;
        ServiceState.error(this, reason);
        notifyMonitor();
    }

    private void notifyMonitor() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_MONITOR, monitorNotification(monitorText));
    }

    private Notification monitorNotification(String text) {
        ServiceState.Snapshot state = ServiceState.read(this);
        String title;
        String notificationText = text;
        switch (state.mode) {
            case ERROR:
                title = getString(R.string.notification_monitor_error_title);
                break;
            case STARTING:
                title = getString(R.string.notification_monitor_starting_title);
                break;
            case RUNNING:
            default:
                title = getString(R.string.notification_monitor_active_title);
                switch (state.collectorSource) {
                    case LOCAL:
                        notificationText = getString(R.string.service_gatt_ready);
                        break;
                    case REMOTE:
                        notificationText = getString(R.string.status_remote_collector_waiting);
                        break;
                    case NONE:
                    default:
                        notificationText = getString(R.string.status_waiting_for_scale);
                        break;
                }
                break;
        }
        PendingIntent open = PendingIntent.getActivity(
                this,
                1,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(
                this,
                2,
                new Intent(this, ScaleScanService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_MONITOR)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(notificationText)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        null,
                        getString(R.string.notification_action_stop),
                        stop).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private Notification resultNotification(String title, String text, boolean error) {
        PendingIntent open = PendingIntent.getActivity(
                this,
                4,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_RESULT)
                .setSmallIcon(error
                        ? android.R.drawable.stat_notify_error
                        : android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(false)
                .setOngoing(false)
                .setOnlyAlertOnce(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build();
    }

    private Notification assignmentNotification(PendingMeasurementStore.Item item, int count) {
        PendingIntent open = PendingIntent.getActivity(
                this,
                3,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = count > 1
                ? getString(
                        R.string.notification_assignment_multiple,
                        item.weightKg,
                        count)
                : getString(
                        R.string.notification_assignment_single,
                        item.weightKg);
        return new Notification.Builder(this, CHANNEL_RESULT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.notification_assignment_title))
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(false)
                .setOngoing(false)
                .setOnlyAlertOnce(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build();
    }

    private Notification remoteAssignmentNotification(
            RemotePendingMeasurementStore.Item item,
            int count) {
        PendingIntent open = PendingIntent.getActivity(
                this,
                5,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String text = count > 1
                ? getString(
                        R.string.notification_remote_assignment_multiple,
                        item.weightKg,
                        count)
                : getString(
                        R.string.notification_remote_assignment_single,
                        item.weightKg);

        return new Notification.Builder(this, CHANNEL_RESULT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(
                        getString(
                                R.string.notification_remote_assignment_title))
                .setContentText(text)
                .setStyle(
                        new Notification.BigTextStyle()
                                .bigText(text))
                .setContentIntent(open)
                .setAutoCancel(false)
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build();
    }

    private void updateAssignmentNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(
                        NOTIFICATION_SERVICE);

        List<PendingMeasurementStore.Item> pending =
                PendingMeasurementStore.load(
                        getSharedPreferences(
                                "prefs",
                                MODE_PRIVATE));

        List<RemotePendingMeasurementStore.Item> remotePending =
                RemotePendingMeasurementStore.load(
                        this);

        if (!pending.isEmpty()) {
            manager.notify(
                    NOTIFICATION_ASSIGNMENT,
                    assignmentNotification(
                            pending.get(0),
                            pending.size()));
            return;
        }

        if (!remotePending.isEmpty()) {
            manager.notify(
                    NOTIFICATION_ASSIGNMENT,
                    remoteAssignmentNotification(
                            remotePending.get(0),
                            remotePending.size()));
            return;
        }

        manager.cancel(
                NOTIFICATION_ASSIGNMENT);
    }

    private void updateMonitor(String text) {
        monitorText = text == null || text.isBlank()
                ? getString(R.string.service_waiting_for_measurement)
                : text;
        if (gattMonitoringActive && !terminalError) {
            ServiceState.running(
                    this,
                    monitorText,
                    gattCollectorOwned,
                    collectorSource());
        } else {
            ServiceState.heartbeat(
                    this,
                    false,
                    monitorText,
                    false,
                    collectorSource());
        }
        notifyMonitor();
    }

    private void createChannels() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel monitor = new NotificationChannel(
                CHANNEL_MONITOR,
                getString(R.string.notification_channel_monitor),
                NotificationManager.IMPORTANCE_LOW);
        monitor.setSound(null, null);
        monitor.enableVibration(false);
        monitor.setShowBadge(false);
        manager.createNotificationChannel(monitor);

        // Version 3.2 uses one dedicated, visible result channel for every completed
        // weighing outcome. A new channel ID is intentional: Android keeps the old
        // sound/importance settings of an existing channel across app updates.
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ASSIGNMENT);
        manager.deleteNotificationChannel(LEGACY_CHANNEL_FAILURE);
        manager.cancel(LEGACY_NOTIFICATION_TRANSFER_FAILURE);

        NotificationChannel result = new NotificationChannel(
                CHANNEL_RESULT,
                getString(R.string.notification_channel_results),
                NotificationManager.IMPORTANCE_DEFAULT);
        result.setDescription(
                getString(R.string.notification_channel_results_description));
        result.enableVibration(true);
        result.setShowBadge(true);
        manager.createNotificationChannel(result);
    }

    @Override public void onDestroy() {
        if (bluetoothStateReceiverRegistered) {
            try {
                unregisterReceiver(
                        bluetoothStateReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            bluetoothStateReceiverRegistered = false;
        }

        stopPeerTransport(
                false);

        handler.removeCallbacksAndMessages(null);
        stopGattCollector();
        if (explicitStop) {
            ServiceState.stopped(
                    this,
                    getString(R.string.service_stopped_by_user));
            EventLog.info(this, getString(R.string.log_service_stopped));
        } else if (terminalError) {
            ServiceState.error(this, monitorText);
            EventLog.warning(this, getString(
                    R.string.log_service_stopped_in_error,
                    monitorText));
        } else {
            ServiceState.starting(
                    this,
                    getString(R.string.service_restart_expected));
            EventLog.warning(
                    this,
                    getString(R.string.log_service_unexpected_stop));
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
