package de.pritcloud.scalelauncher;

import android.Manifest;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.util.List;

/**
 * Address-filtered PendingIntent scan registration.
 * Does not initiate GATT connections or start services.
 */
final class S400WakeupScan {
    static final int UNAVAILABLE = -1;
    private static final int REQUEST_CODE = 50400;

    private S400WakeupScan() {}

    static boolean shouldFallbackToAllMatches(int code) {
        return code == ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED
                || code == ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES;
    }

    static int start(Context context, String address) {
        BluetoothLeScanner scanner = scanner(context);

        if (scanner == null
                || !S400GattProtocol.isValidMacAddress(address)) {
            return UNAVAILABLE;
        }

        ScanFilter filter =
                new ScanFilter.Builder()
                        .setDeviceAddress(address)
                        .build();

        ScanSettings trackingSettings =
                new ScanSettings.Builder()
                        .setScanMode(
                                ScanSettings.SCAN_MODE_LOW_POWER)
                        .setCallbackType(
                                ScanSettings.CALLBACK_TYPE_FIRST_MATCH
                                        | ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                        .build();

        ScanSettings fallbackSettings =
                new ScanSettings.Builder()
                        .setScanMode(
                                ScanSettings.SCAN_MODE_LOW_POWER)
                        .setCallbackType(
                                ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .build();

        PendingIntent callbackIntent =
                pendingIntent(
                        context,
                        PendingIntent.FLAG_UPDATE_CURRENT);

        try {
            boolean useAllMatches =
                    context.getSharedPreferences(
                            "s400_wakeup_diagnostics",
                            Context.MODE_PRIVATE)
                            .getBoolean(
                                    "force_all_matches", false);

            int result = scanner.startScan(
                    List.of(filter),
                    useAllMatches
                            ? fallbackSettings
                            : trackingSettings,
                    callbackIntent);

            if (!useAllMatches
                    && shouldFallbackToAllMatches(result)) {
                EventLog.warning(
                        context,
                        "S400-Wakeup: Hardware-Tracking nicht verfügbar"
                                + " – ALL_MATCHES-Fallback");

                result = scanner.startScan(
                        List.of(filter),
                        fallbackSettings,
                        callbackIntent);
            }

            if (result != 0
                    && result
                            != ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
                callbackIntent.cancel();
                EventLog.warning(
                        context,
                        "S400-Wakeup: Scan-Registrierung abgelehnt – code="
                                + result);
            }

            return result;
        } catch (RuntimeException exception) {
            callbackIntent.cancel();
            EventLog.debug(
                    context,
                    "S400-Wakeup: Registrierung fehlgeschlagen – "
                            + exception.getClass().getSimpleName());
            return UNAVAILABLE;
        }
    }

    static boolean stop(Context context) {
        PendingIntent callbackIntent =
                pendingIntent(
                        context,
                        PendingIntent.FLAG_NO_CREATE);

        if (callbackIntent == null) {
            return true;
        }

        BluetoothLeScanner scanner = scanner(context);

        if (scanner == null) {
            return false;
        }

        try {
            scanner.stopScan(callbackIntent);
            callbackIntent.cancel();
            return true;
        } catch (RuntimeException exception) {
            EventLog.debug(
                    context,
                    "S400-Wakeup: Abmeldung fehlgeschlagen – "
                            + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static PendingIntent pendingIntent(
            Context context,
            int flags) {
        Intent intent =
                new Intent(
                        context,
                        S400WakeupReceiver.class);
        intent.setAction(S400WakeupReceiver.ACTION);

        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_MUTABLE | flags);
    }

    private static BluetoothLeScanner scanner(
            Context context) {
        if (context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(
                        Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            return null;
        }

        BluetoothManager manager =
                context.getSystemService(
                        BluetoothManager.class);

        BluetoothAdapter adapter =
                manager == null ? null : manager.getAdapter();

        if (adapter == null || !adapter.isEnabled()) {
            return null;
        }

        return adapter.getBluetoothLeScanner();
    }
}
