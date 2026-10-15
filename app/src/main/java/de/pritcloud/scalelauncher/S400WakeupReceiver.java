package de.pritcloud.scalelauncher;

import android.Manifest;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanResult;
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
    static final String ACTION =
            "de.pritcloud.scalelauncher.S400_WAKEUP_SCAN";
    private static final long MAX_SIGNAL_AGE_MS = 5_000L;
    private static final long LOG_COOLDOWN_MS = 60_000L;

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
            EventLog.debug(context,
                    "S400-Wakeup: Scanfehler "
                            + intent.getIntExtra(
                                    BluetoothLeScanner.EXTRA_ERROR_CODE, -1));
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
