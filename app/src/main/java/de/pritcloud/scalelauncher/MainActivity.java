package de.pritcloud.scalelauncher;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 100;
    private static final int REQ_SCAN = 101;
    private static final Pattern MAC_PATTERN = Pattern.compile("^([0-9A-F]{2}:){5}[0-9A-F]{2}$");

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refreshLog();
            refreshHandler.postDelayed(this, 1_000L);
        }
    };

    private EditText macAddress;
    private EditText absenceSeconds;
    private CheckBox autoStart;
    private TextView status;
    private TextView log;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        macAddress = findViewById(R.id.macAddress);
        absenceSeconds = findViewById(R.id.absenceSeconds);
        autoStart = findViewById(R.id.autoStart);
        status = findViewById(R.id.status);
        log = findViewById(R.id.log);

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        macAddress.setText(prefs.getString("mac", ""));
        absenceSeconds.setText(String.valueOf(prefs.getInt("absence_seconds", 8)));
        autoStart.setChecked(prefs.getBoolean("autoStart", false));

        findViewById(R.id.scanDevice).setOnClickListener(v -> {
            if (hasBluetoothPermissions()) {
                startActivityForResult(new Intent(this, DeviceScanActivity.class), REQ_SCAN);
            } else {
                requestNeededPermissions();
            }
        });
        findViewById(R.id.saveStart).setOnClickListener(v -> saveAndStart());
        findViewById(R.id.stop).setOnClickListener(v -> {
            stopService(new Intent(this, ScaleScanService.class));
            status.setText("Status: gestoppt");
        });
        findViewById(R.id.testOpenScale).setOnClickListener(v -> {
            Intent intent = new Intent(this, ScaleScanService.class).setAction(ScaleScanService.ACTION_TEST_OPEN);
            startForegroundService(intent);
            Toast.makeText(this, "Teststart angefordert", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.refreshLog).setOnClickListener(v -> refreshLog());
        findViewById(R.id.clearLog).setOnClickListener(v -> {
            EventLog.clear(this);
            refreshLog();
        });
        findViewById(R.id.notificationSettings).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        });

        requestNeededPermissions();
        refreshLog();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshHandler.post(refreshTask);
    }

    @Override protected void onPause() {
        refreshHandler.removeCallbacks(refreshTask);
        super.onPause();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK && data != null) {
            String mac = data.getStringExtra("mac");
            if (mac != null) {
                macAddress.setText(mac);
                EventLog.add(this, "Waage ausgewählt: " + mac);
                refreshLog();
            }
        }
    }

    private void saveAndStart() {
        if (!hasBluetoothPermissions()) {
            requestNeededPermissions();
            Toast.makeText(this, "Bitte zuerst die Bluetooth-Berechtigungen erlauben.", Toast.LENGTH_LONG).show();
            return;
        }

        String mac = macAddress.getText().toString().trim().toUpperCase(Locale.ROOT);
        if (!MAC_PATTERN.matcher(mac).matches()) {
            Toast.makeText(this, "Bitte eine gültige MAC-Adresse eintragen oder eine Waage auswählen.", Toast.LENGTH_LONG).show();
            return;
        }

        int absence = 8;
        try {
            absence = Integer.parseInt(absenceSeconds.getText().toString().trim());
        } catch (NumberFormatException ignored) {
        }
        absence = Math.max(4, Math.min(absence, 30));
        absenceSeconds.setText(String.valueOf(absence));

        getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putString("mac", mac)
                .putInt("absence_seconds", absence)
                .putBoolean("autoStart", autoStart.isChecked())
                .apply();

        EventLog.add(this, "Konfiguration gespeichert");
        startForegroundService(new Intent(this, ScaleScanService.class));
        status.setText("Status: Überwachung angefordert");
    }

    private boolean hasBluetoothPermissions() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS
            }, REQ_PERMISSIONS);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, REQ_PERMISSIONS);
        }
    }

    private void refreshLog() {
        log.setText(EventLog.read(this));
    }
}
