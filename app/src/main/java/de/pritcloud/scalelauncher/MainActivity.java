package de.pritcloud.scalelauncher;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 100;
    private static final int REQ_SCAN = 101;
    private static final Pattern MAC = Pattern.compile("^([0-9A-F]{2}:){5}[0-9A-F]{2}$");
    private EditText macAddress;
    private CheckBox autoStart;
    private TextView status;
    private TextView log;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        macAddress = findViewById(R.id.macAddress);
        autoStart = findViewById(R.id.autoStart);
        status = findViewById(R.id.status);
        log = findViewById(R.id.log);

        SharedPreferences p = getSharedPreferences("prefs", MODE_PRIVATE);
        macAddress.setText(p.getString("mac", ""));
        autoStart.setChecked(p.getBoolean("autoStart", false));

        findViewById(R.id.scanDevice).setOnClickListener(v -> startActivityForResult(new Intent(this, DeviceScanActivity.class), REQ_SCAN));
        findViewById(R.id.saveStart).setOnClickListener(v -> saveAndStart());
        findViewById(R.id.stop).setOnClickListener(v -> {
            stopService(new Intent(this, ScaleScanService.class));
            status.setText("Status: gestoppt");
            refreshLog();
        });
        findViewById(R.id.refreshLog).setOnClickListener(v -> refreshLog());
        findViewById(R.id.clearLog).setOnClickListener(v -> { EventLog.clear(this); refreshLog(); });
        findViewById(R.id.notificationSettings).setOnClickListener(v -> openNotificationSettings());
        requestNeededPermissions();
        refreshLog();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshLog();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK && data != null) {
            String mac = data.getStringExtra("mac");
            if (mac != null) macAddress.setText(mac);
            Toast.makeText(this, "Waage ausgewählt", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveAndStart() {
        String mac = macAddress.getText().toString().trim().toUpperCase(Locale.ROOT);
        if (!MAC.matcher(mac).matches()) {
            Toast.makeText(this, "Bitte eine gültige MAC-Adresse eintragen oder die Waage suchen.", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putString("mac", mac)
                .putBoolean("autoStart", autoStart.isChecked())
                .apply();
        requestNeededPermissions();
        startForegroundService(new Intent(this, ScaleScanService.class));
        status.setText("Status: Überwachung aktiv");
        refreshLog();
    }

    private void refreshLog() { log.setText(EventLog.read(this)); }

    private void openNotificationSettings() {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(i);
    }

    private void requestNeededPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS);
        } else {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERMS);
        }
    }
}
