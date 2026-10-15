package de.pritcloud.scalelauncher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/** Bounded, address-filtered S400 observation. No GATT operations. */
final class S400PresenceProbe {
    interface Listener {
        void onSeen(long elapsedMs);
        void onScanFailed(int errorCode);
        void onCompleted(boolean scaleSeen);
    }

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private Runnable stopTask;
    private long deadlineElapsedMs;

    boolean isActive() {
        return callback != null;
    }

    boolean start(
            BluetoothLeScanner candidate,
            String address,
            long durationMs,
            Listener listener) {
        if (isActive()
                || candidate == null
                || listener == null
                || durationMs <= 0L
                || !BluetoothAdapter.checkBluetoothAddress(address)) {
            return false;
        }

        final long startedElapsedMs =
                SystemClock.elapsedRealtime();
        final long deadlineElapsedMs =
                startedElapsedMs + durationMs;

        ScanFilter filter =
                new ScanFilter.Builder()
                        .setDeviceAddress(address)
                        .build();

        ScanSettings settings =
                new ScanSettings.Builder()
                        .setScanMode(
                                ScanSettings.SCAN_MODE_LOW_POWER)
                        .build();

        ScanCallback nextCallback = new ScanCallback() {
            @Override
            public void onScanResult(
                    int callbackType,
                    ScanResult result) {
                if (result == null
                        || result.getDevice() == null) {
                    return;
                }

                long seenAt =
                        result.getTimestampNanos() / 1_000_000L;
                String seenAddress;
                try {
                    seenAddress = result.getDevice().getAddress();
                } catch (SecurityException exception) {
                    return;
                }

                handler.post(() -> {
                    if (callback != this) {
                        return;
                    }

                    long receivedElapsedMs =
                            SystemClock.elapsedRealtime();

                    if (receivedElapsedMs >= deadlineElapsedMs) {
                        stop();
                        listener.onCompleted(false);
                        return;
                    }

                    if (address.equalsIgnoreCase(seenAddress)
                            && seenAt >= startedElapsedMs
                            && seenAt <= receivedElapsedMs
                            && receivedElapsedMs - seenAt <= 5_000L) {
                        stop();
                        listener.onSeen(seenAt);
                        listener.onCompleted(true);
                        return;
                    }

                });
            }

            @Override
            public void onScanFailed(int errorCode) {
                handler.post(() -> {
                    if (callback != this) {
                        return;
                    }
                    stop();
                    listener.onScanFailed(errorCode);
                });
            }
        };

        try {
            candidate.startScan(
                    List.of(filter),
                    settings,
                    nextCallback);
        } catch (RuntimeException exception) {
            return false;
        }

        scanner = candidate;
        callback = nextCallback;
        this.deadlineElapsedMs = deadlineElapsedMs;
        stopTask = () -> {
            if (callback != nextCallback) {
                return;
            }
            stop();
            listener.onCompleted(false);
        };
        handler.postDelayed(stopTask, durationMs);
        return true;
    }

    void expireIfOverdue() {
        if (stopTask != null
                && SystemClock.elapsedRealtime()
                        >= deadlineElapsedMs) {
            stopTask.run();
        }
    }

    void stop() {
        if (stopTask != null) {
            handler.removeCallbacks(stopTask);
            stopTask = null;
        }

        BluetoothLeScanner previousScanner = scanner;
        ScanCallback previousCallback = callback;
        scanner = null;
        callback = null;
        deadlineElapsedMs = 0L;

        if (previousScanner != null
                && previousCallback != null) {
            try {
                previousScanner.stopScan(previousCallback);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
