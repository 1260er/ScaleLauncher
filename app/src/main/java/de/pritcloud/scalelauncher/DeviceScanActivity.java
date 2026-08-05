package de.pritcloud.scalelauncher;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

public class DeviceScanActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, ScanResult> results = new LinkedHashMap<>();
    private ArrayAdapter<String> adapter;
    private BluetoothLeScanner scanner;
    private ScanCallback callback;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_device_scan);
        ListView list = findViewById(R.id.deviceList);
        TextView info = findViewById(R.id.scanInfo);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            ScanResult result = results.values().toArray(new ScanResult[0])[position];
            Intent data = new Intent();
            data.putExtra("mac", result.getDevice().getAddress());
            String name = result.getDevice().getName();
            data.putExtra("name", name == null ? getString(R.string.scan_unknown_device) : name);
            setResult(RESULT_OK, data);
            finish();
        });
        info.setText(R.string.scan_info);
        startScan();
        handler.postDelayed(this::finishScan, 12_000);
    }

    private void startScan() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 200);
            return;
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter bt = manager == null ? null : manager.getAdapter();
        scanner = bt == null ? null : bt.getBluetoothLeScanner();
        if (scanner == null) return;
        callback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                String address = result.getDevice().getAddress();
                results.put(address, result);
                rebuildList();
            }
        };
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(null, settings, callback);
    }

    private void rebuildList() {
        adapter.clear();
        for (ScanResult result : results.values()) {
            String name = result.getDevice().getName();
            if (name == null || name.trim().isEmpty()) name = getString(R.string.scan_unknown_device);
            adapter.add(name + "\n" + result.getDevice().getAddress() + "   RSSI " + result.getRssi());
        }
        adapter.notifyDataSetChanged();
    }

    private void finishScan() {
        if (scanner != null && callback != null && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            scanner.stopScan(callback);
        }
    }

    @Override protected void onDestroy() {
        finishScan();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
