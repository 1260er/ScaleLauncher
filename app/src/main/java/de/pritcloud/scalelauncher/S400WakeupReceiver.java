package de.pritcloud.scalelauncher;

import android.Manifest;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import java.util.ArrayList;

/** Diagnostic receiver for filtered S400 PendingIntent scan results. */
public final class S400WakeupReceiver extends BroadcastReceiver {
    interface Listener {
        void onWakeup(String mac, long signalElapsedMs);
    }

    private static volatile Listener listener;

    static void setListener(Listener value) {
        listener = value;
    }

    static void clearListener(Listener value) {
        if (listener == value) {
            listener = null;
        }
    }
    static final String ACTION =
            "de.pritcloud.scalelauncher.S400_WAKEUP_SCAN";
    private static final long MAX_SIGNAL_AGE_MS = 5_000L;
    private static final long LOG_COOLDOWN_MS = 60_000L;
    private static final long SIGNAL_INTERVAL_MS = 2_000L;

    static boolean shouldHandleSignal(
            long previousElapsedMs,
            long signalElapsedMs) {
        return signalElapsedMs >= 0L
                && (previousElapsedMs < 0L
                    || signalElapsedMs < previousElapsedMs
                    || signalElapsedMs - previousElapsedMs
                            >= SIGNAL_INTERVAL_MS);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }

        if (ServiceState.read(context).mode
                == ServiceState.Mode.STOPPED) {
            return;
        }

        if (context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        SharedPreferences prefs =
                context.getSharedPreferences("prefs", Context.MODE_PRIVATE);

        String target = prefs.getString("mac", "");
        if (!S400GattProtocol.isValidMacAddress(target)) {
            return;
        }

        if (intent.hasExtra(BluetoothLeScanner.EXTRA_ERROR_CODE)) {
            int code = intent.getIntExtra(
                    BluetoothLeScanner.EXTRA_ERROR_CODE, -1);

            SharedPreferences.Editor diagnostics =
                    context.getSharedPreferences(
                            "s400_wakeup_diagnostics",
                            Context.MODE_PRIVATE)
                            .edit();

            diagnostics.putLong(
                    "last_scan_error_elapsed_ms",
                    SystemClock.elapsedRealtime());

            if (S400WakeupScan.shouldFallbackToAllMatches(code)) {
                diagnostics.putBoolean(
                        "force_all_matches", true);
            }

            diagnostics.apply();

            EventLog.warning(
                    context,
                    "S400-Wakeup: Scanfehler " + code);
            return;
        }

        int callbackType = intent.getIntExtra(
                BluetoothLeScanner.EXTRA_CALLBACK_TYPE,
                ScanSettings.CALLBACK_TYPE_ALL_MATCHES);

        // A lost device is not a new S400 wakeup event.
        if ((callbackType & ScanSettings.CALLBACK_TYPE_MATCH_LOST) != 0) {
            return;
        }

        ArrayList<ScanResult> results;

        if (Build.VERSION.SDK_INT >= 33) {
            results = intent.getParcelableArrayListExtra(
                    BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                    ScanResult.class);
        } else {
            results = readLegacyResults(intent);
        }

        if (results == null) {
            return;
        }

        long now = SystemClock.elapsedRealtime();

        for (ScanResult result : results) {
            if (result == null || result.getDevice() == null) {
                continue;
            }

            String address;
            try {
                address = result.getDevice().getAddress();
            } catch (SecurityException exception) {
                continue;
            }

            long signalElapsedMs =
                    result.getTimestampNanos() / 1_000_000L;
            long ageMs = now - signalElapsedMs;

            if (!target.equalsIgnoreCase(address)
                    || ageMs < 0L
                    || ageMs > MAX_SIGNAL_AGE_MS) {
                continue;
            }

            SharedPreferences state =
                    context.getSharedPreferences(
                            "s400_wakeup_diagnostics",
                            Context.MODE_PRIVATE);

            long previousSignalMs =
                    state.getLong(
                            "last_valid_signal_elapsed_ms", -1L);

            if (!shouldHandleSignal(
                    previousSignalMs, signalElapsedMs)) {
                return;
            }

            // Keep accepted observations even during log cooldown.
            state.edit()
                    .putLong(
                            "last_valid_signal_elapsed_ms",
                            signalElapsedMs)
                    .putLong(
                            "last_valid_signal_wall_ms",
                            System.currentTimeMillis())
                    .putString(
                            "last_valid_signal_mac",
                            target)
                    .apply();

            Listener currentListener = listener;
            if (currentListener != null) {
                currentListener.onWakeup(target, signalElapsedMs);
            }

            long previous =
                    state.getLong("last_log_elapsed_ms", -1L);

            if (previous >= 0L
                    && now >= previous
                    && now - previous < LOG_COOLDOWN_MS) {
                return;
            }

            state.edit()
                    .putLong("last_log_elapsed_ms", now)
                    .apply();

            EventLog.info(context,
                    "S400-Wakeup: frisches BLE-Signal erkannt"
                            + " signalAgeMs=" + ageMs);
            return;
        }
    }

    @SuppressWarnings("deprecation")
    private static ArrayList<ScanResult> readLegacyResults(
            Intent intent) {
        return intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT);
    }
}
