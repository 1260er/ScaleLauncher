package de.pritcloud.scalelauncher;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 100;
    private static final Pattern MAC = Pattern.compile("^([0-9A-F]{2}:){5}[0-9A-F]{2}$");
    private EditText macAddress;
    private CheckBox autoStart;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        macAddress = findViewById(R.id.macAddress);
        autoStart = findViewById(R.id.autoStart);
        status = findViewById(R.id.status);
        Button saveStart = findViewById(R.id.saveStart);
        Button stop = findViewById(R.id.stop);

        SharedPreferences p = getSharedPreferences("prefs", MODE_PRIVATE);
        macAddress.setText(p.getString("mac", ""));
        autoStart.setChecked(p.getBoolean("autoStart", false));

        saveStart.setOnClickListener(v -> saveAndStart());
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, ScaleScanService.class));
            status.setText("Status: gestoppt");
        });
        requestNeededPermissions();
    }

    private void saveAndStart() {
        String mac = macAddress.getText().toString().trim().toUpperCase(Locale.ROOT);
        if (!MAC.matcher(mac).matches()) {
            Toast.makeText(this, "Bitte eine gültige MAC-Adresse eintragen.", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putString("mac", mac)
                .putBoolean("autoStart", autoStart.isChecked())
                .apply();
        requestNeededPermissions();
        startForegroundService(new Intent(this, ScaleScanService.class));
        status.setText("Status: Überwachung aktiv");
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
