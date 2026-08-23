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
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

public final class ScaleScanService extends Service {
    public static final String ACTION_STOP = "de.pritcloud.scalelauncher.STOP";
    public static final String ACTION_ASSIGN_PENDING = "de.pritcloud.scalelauncher.ASSIGN_PENDING";
    public static final String ACTION_REFRESH_PENDING = "de.pritcloud.scalelauncher.REFRESH_PENDING";
    public static final String EXTRA_PENDING_ID = "pending_id";
    public static final String EXTRA_USER_ID = "user_id";

    private static final String CHANNEL_MONITOR = "scale_monitor_v10";
    private static final String CHANNEL_RESULT = "scale_measurement_results_v1";
    private static final String LEGACY_CHANNEL_ASSIGNMENT = "scale_assignment_v1";
    private static final String LEGACY_CHANNEL_FAILURE = "scale_measurement_failure_v1";
    private static final int NOTIFICATION_MONITOR = 10;
    private static final int NOTIFICATION_ASSIGNMENT = 11;
    private static final int NOTIFICATION_RESULT = 12;
    private static final int LEGACY_NOTIFICATION_TRANSFER_FAILURE = 13;
    private static final long WATCHDOG_INTERVAL_MS = 15_000L;
    private static final long GATT_RECONNECT_BASE_MS = 5_000L;
    private static final long GATT_RECONNECT_MAX_MS = 60_000L;
    private static final long USER_SYNC_INTERVAL_MS = 15 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdogRunnable = this::runWatchdog;
    private final Runnable gattReconnectRunnable = this::runGattReconnect;
    private final Runnable userSyncRunnable = new Runnable() {
        @Override public void run() {
            synchronizeOpenScaleUsers();
            if (!explicitStop) {
                handler.postDelayed(this, USER_SYNC_INTERVAL_MS);
            }
        }
    };

    private S400GattClient gattClient;
    private boolean gattMonitoringActive;
    private boolean gattCollectorOwned;
    private boolean gattReconnectScheduled;
    private int gattReconnectAttempt;
    private long lastGattFinalTimestampSeconds;
    private boolean explicitStop;
    private boolean terminalError;
    private String monitorText = "";

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
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
        } else if (intent != null && ACTION_REFRESH_PENDING.equals(intent.getAction())) {
            updateAssignmentNotification();
        } else {
            terminalError = false;
        }
        if (!terminalError) startGattCollector();
        return START_STICKY;
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
        gattCollectorOwned = false;
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
        ServiceState.running(this, monitorText, false);
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
                                    false);
                            notifyMonitor();
                        }

                        if (state == S400GattClient.State.DISCONNECTED
                                && gattMonitoringActive) {
                            gattCollectorOwned = false;
                            gattClient = null;
                            scheduleGattReconnect(
                                    getString(
                                            R.string.service_error_gatt_inactive));
                        }
                    }

                    @Override public void onAuthenticated() {
                        gattCollectorOwned = true;
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
        gattCollectorOwned = false;

        S400GattClient oldClient = gattClient;
        gattClient = null;

        monitorText = getString(
                R.string.service_gatt_reconnecting,
                delayMs / 1000L);
        ServiceState.running(this, monitorText, true);
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
                    UserProfileStore.synchronize(prefs, currentUsers);

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

    private void routeMeasurement(S400FinalMeasurement measurement) {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        List<UserProfile> profiles = UserProfileStore.enabled(UserProfileStore.load(prefs));
        UserMatcher.Result match = UserMatcher.match(profiles, measurement.weightKg);
        EventLog.debug(this, getString(
                R.string.log_user_match,
                UserMatcher.diagnosticSummary(this, match)));

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
        PendingMeasurementStore.Item pending = PendingMeasurementStore.add(
                prefs,
                measurement,
                reason);
        EventLog.warning(this, getString(
                R.string.log_measurement_unassigned,
                measurement.weightKg,
                reason));
        EventLog.debug(this, getString(R.string.log_pending_measurement_saved, pending.id));
        updateMonitor(getString(R.string.service_user_assignment_required));
        updateAssignmentNotification();
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
            PendingMeasurementStore.remove(prefs, pending.id);
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
                float average = OpenScaleProvider.readAverageRecentWeight(
                        this,
                        authority,
                        profile.userId,
                        5);
                if (average > 0f) {
                    UserProfileStore.updateReferenceWeight(prefs, profile.userId, average);
                    EventLog.debug(this, getString(
                            R.string.log_reference_weight_updated,
                            profile.name,
                            average));
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
        updateMonitor(getString(
                R.string.service_measurement_saved_for,
                userName));
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

    private void runWatchdog() {
        if (explicitStop) return;

        if (terminalError) {
            ServiceState.heartbeat(this, false, monitorText);
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
                        ready);
            }
        }

        handler.removeCallbacks(watchdogRunnable);
        handler.postDelayed(
                watchdogRunnable,
                WATCHDOG_INTERVAL_MS);
    }

    private void enterTerminalError(String reason) {
        terminalError = true;
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
                .setContentText(text)
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

    private void updateAssignmentNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        List<PendingMeasurementStore.Item> pending = PendingMeasurementStore.load(
                getSharedPreferences("prefs", MODE_PRIVATE));
        if (pending.isEmpty()) {
            manager.cancel(NOTIFICATION_ASSIGNMENT);
        } else {
            manager.notify(
                    NOTIFICATION_ASSIGNMENT,
                    assignmentNotification(pending.get(0), pending.size()));
        }
    }

    private void updateMonitor(String text) {
        monitorText = text == null || text.isBlank()
                ? getString(R.string.service_waiting_for_measurement)
                : text;
        if (gattMonitoringActive && !terminalError) {
            ServiceState.running(
                    this,
                    monitorText,
                    gattCollectorOwned);
        } else {
            ServiceState.heartbeat(this, false, monitorText);
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
