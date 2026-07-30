package de.pritcloud.scalelauncher;

import android.Manifest;
import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 100;
    private static final int REQ_SCAN = 101;
    private static final int REQ_OPENSCALE_PERMISSION = 102;
    private static final int REQ_HEALTH_CONNECT = 103;
    private static final Pattern MAC_PATTERN = Pattern.compile("^([0-9A-F]{2}:){5}[0-9A-F]{2}$");
    private static final Pattern KEY_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refreshLog();
            refreshHandler.postDelayed(this, 1_000L);
        }
    };

    private EditText macAddress;
    private EditText bindKey;
    private EditText birthDate;
    private EditText heightCm;
    private CheckBox autoStart;
    private CheckBox healthConnectEnabled;
    private CheckBox hcWeight;
    private CheckBox hcBodyFat;
    private CheckBox hcBodyWater;
    private CheckBox hcBoneMass;
    private CheckBox hcLeanBodyMass;
    private CheckBox hcBmr;
    private CheckBox hcHeartRate;
    private CheckBox diagnosticLogging;
    private RadioButton sexMale;
    private Spinner userSpinner;
    private TextView status;
    private TextView log;
    private TextView openScaleStatus;
    private TextView healthConnectStatus;
    private String openScaleAuthority;
    private OpenScaleProvider.Meta openScaleMeta = new OpenScaleProvider.Meta(1, -1);
    private List<OpenScaleProvider.User> users = new ArrayList<>();
    private LocalDate selectedBirthDate;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        macAddress = findViewById(R.id.macAddress);
        bindKey = findViewById(R.id.bindKey);
        birthDate = findViewById(R.id.birthDate);
        heightCm = findViewById(R.id.heightCm);
        autoStart = findViewById(R.id.autoStart);
        healthConnectEnabled = findViewById(R.id.healthConnectEnabled);
        hcWeight = findViewById(R.id.hcWeight);
        hcBodyFat = findViewById(R.id.hcBodyFat);
        hcBodyWater = findViewById(R.id.hcBodyWater);
        hcBoneMass = findViewById(R.id.hcBoneMass);
        hcLeanBodyMass = findViewById(R.id.hcLeanBodyMass);
        hcBmr = findViewById(R.id.hcBmr);
        hcHeartRate = findViewById(R.id.hcHeartRate);
        diagnosticLogging = findViewById(R.id.diagnosticLogging);
        sexMale = findViewById(R.id.sexMale);
        userSpinner = findViewById(R.id.openScaleUser);
        status = findViewById(R.id.status);
        log = findViewById(R.id.log);
        openScaleStatus = findViewById(R.id.openScaleStatus);
        healthConnectStatus = findViewById(R.id.healthConnectStatus);

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        macAddress.setText(prefs.getString("mac", ""));
        bindKey.setText(prefs.getString("bind_key", ""));
        selectedBirthDate = BirthDateUtils.parseIso(prefs.getString("birth_date", ""));
        updateBirthDateText();
        float storedHeight = prefs.getFloat("height_cm", 0f);
        heightCm.setText(storedHeight == 0f ? "" : String.valueOf(storedHeight));
        autoStart.setChecked(prefs.getBoolean("autoStart", false));
        diagnosticLogging.setChecked(prefs.getBoolean("diagnostic_logging", false));

        HealthConnectSelection storedSelection = HealthConnectSelection.fromPreferences(prefs);
        applyHealthConnectSelection(storedSelection);
        healthConnectEnabled.setChecked(
                prefs.getBoolean("health_connect_enabled", false)
                        && HealthConnectSupport.hasWritePermissions(this, storedSelection));

        if (prefs.getInt("sex", 0) == 1) sexMale.setChecked(true);
        else ((RadioButton) findViewById(R.id.sexFemale)).setChecked(true);

        birthDate.setOnClickListener(v -> showBirthDatePicker());
        findViewById(R.id.scanDevice).setOnClickListener(v -> {
            if (hasBluetoothPermissions()) {
                startActivityForResult(new Intent(this, DeviceScanActivity.class), REQ_SCAN);
            } else {
                requestNeededPermissions();
            }
        });
        findViewById(R.id.loadOpenScaleUsers).setOnClickListener(v -> prepareOpenScaleAccess());
        findViewById(R.id.connectHealthConnect).setOnClickListener(
                v -> requestHealthConnectPermissions());
        findViewById(R.id.saveStart).setOnClickListener(v -> saveAndStart());
        findViewById(R.id.stop).setOnClickListener(v -> {
            stopService(new Intent(this, ScaleScanService.class));
            status.setText("Status: gestoppt");
        });
        findViewById(R.id.refreshLog).setOnClickListener(v -> refreshLog());
        findViewById(R.id.copyLog).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    "ScaleLauncher-Protokoll",
                    EventLog.read(this)));
            Toast.makeText(this, "Protokoll kopiert", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.clearLog).setOnClickListener(v -> {
            EventLog.clear(this);
            refreshLog();
        });
        findViewById(R.id.notificationSettings).setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));

        android.widget.CompoundButton.OnCheckedChangeListener selectionListener =
                (button, checked) -> refreshHealthConnectStatus();
        hcWeight.setOnCheckedChangeListener(selectionListener);
        hcBodyFat.setOnCheckedChangeListener(selectionListener);
        hcBodyWater.setOnCheckedChangeListener(selectionListener);
        hcBoneMass.setOnCheckedChangeListener(selectionListener);
        hcLeanBodyMass.setOnCheckedChangeListener(selectionListener);
        hcBmr.setOnCheckedChangeListener(selectionListener);
        hcHeartRate.setOnCheckedChangeListener(selectionListener);

        diagnosticLogging.setOnCheckedChangeListener((button, enabled) -> {
            getSharedPreferences("prefs", MODE_PRIVATE).edit()
                    .putBoolean("diagnostic_logging", enabled)
                    .apply();
            EventLog.info(this, enabled
                    ? "Diagnoseprotokoll aktiviert"
                    : "Diagnoseprotokoll deaktiviert");
            refreshLog();
        });

        TextView logInfo = findViewById(R.id.logInfo);
        logInfo.setText("Im Normalmodus werden nur wichtige Statusmeldungen, erfolgreiche "
                + "Übergaben und Fehler gespeichert. Alte Einträge werden automatisch gelöscht ("
                + EventLog.limitDescription()
                + ").");

        requestNeededPermissions();
        refreshLog();
        prepareOpenScaleAccess();
        refreshHealthConnectStatus();
    }

    private void showBirthDatePicker() {
        LocalDate initial = selectedBirthDate != null
                ? selectedBirthDate
                : LocalDate.now().minusYears(40);
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    selectedBirthDate = LocalDate.of(year, month + 1, dayOfMonth);
                    updateBirthDateText();
                },
                initial.getYear(),
                initial.getMonthValue() - 1,
                initial.getDayOfMonth());
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.show();
    }

    private void updateBirthDateText() {
        birthDate.setText(BirthDateUtils.toDisplay(selectedBirthDate));
        int currentAge = BirthDateUtils.ageToday(selectedBirthDate);
        birthDate.setHint(currentAge >= 0
                ? "Aktuell " + currentAge + " Jahre"
                : "Geburtstag auswählen");
    }

    private void prepareOpenScaleAccess() {
        openScaleAuthority = OpenScaleProvider.findAuthority(this);
        if (openScaleAuthority == null) {
            openScaleStatus.setText(
                    "openScale-Provider nicht gefunden. Aktuelle openScale-Version installieren.");
            users = new ArrayList<>();
            updateUserSpinner(-1L);
            return;
        }
        String permission = OpenScaleProvider.permissionForAuthority(openScaleAuthority);
        if (permission != null
                && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            openScaleStatus.setText("openScale-Zugriff muss erlaubt werden");
            requestPermissions(new String[]{permission}, REQ_OPENSCALE_PERMISSION);
            return;
        }
        loadOpenScaleUsers();
    }

    private void loadOpenScaleUsers() {
        try {
            openScaleMeta = OpenScaleProvider.readMeta(this, openScaleAuthority);
            users = OpenScaleProvider.loadUsers(this, openScaleAuthority);
            long storedUser = getSharedPreferences("prefs", MODE_PRIVATE)
                    .getLong("openscale_user_id", -1L);
            updateUserSpinner(storedUser);

            String apiStatus = openScaleMeta.supportsGenericValues()
                    ? "Provider-API " + openScaleMeta.apiVersion + ": vollständige Messwerte möglich"
                    : "Provider-API 1: extern nur Gewicht, Fett, Wasser und Muskel";
            openScaleStatus.setText(users.isEmpty()
                    ? "openScale gefunden, aber keine Benutzer verfügbar – " + apiStatus
                    : "openScale verbunden: " + users.size() + " Benutzer – " + apiStatus);
        } catch (SecurityException e) {
            openScaleStatus.setText("openScale-Zugriff verweigert");
        } catch (RuntimeException e) {
            openScaleStatus.setText(
                    "openScale-Abfrage fehlgeschlagen: " + e.getClass().getSimpleName());
        }
    }

    private void updateUserSpinner(long selectedId) {
        ArrayAdapter<OpenScaleProvider.User> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                users);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        userSpinner.setAdapter(adapter);
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i).id == selectedId) userSpinner.setSelection(i);
        }
    }

    private void applyHealthConnectSelection(HealthConnectSelection selection) {
        hcWeight.setChecked(selection.weight);
        hcBodyFat.setChecked(selection.bodyFat);
        hcBodyWater.setChecked(selection.bodyWater);
        hcBoneMass.setChecked(selection.boneMass);
        hcLeanBodyMass.setChecked(selection.leanBodyMass);
        hcBmr.setChecked(selection.basalMetabolicRate);
        hcHeartRate.setChecked(selection.heartRate);
    }

    private HealthConnectSelection healthConnectSelectionFromUi() {
        return new HealthConnectSelection(
                hcWeight.isChecked(),
                hcBodyFat.isChecked(),
                hcBodyWater.isChecked(),
                hcBoneMass.isChecked(),
                hcLeanBodyMass.isChecked(),
                hcBmr.isChecked(),
                hcHeartRate.isChecked());
    }

    @Override protected void onResume() {
        super.onResume();
        refreshHealthConnectStatus();
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
                EventLog.info(this, "Waage ausgewählt: " + mac);
            }
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,
                                                     String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_OPENSCALE_PERMISSION) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadOpenScaleUsers();
            } else {
                openScaleStatus.setText("openScale-Zugriff wurde nicht erlaubt");
            }
            return;
        }
        if (requestCode == REQ_HEALTH_CONNECT) {
            HealthConnectSelection selection = healthConnectSelectionFromUi();
            refreshHealthConnectStatus();
            boolean granted = HealthConnectSupport.hasWritePermissions(this, selection);
            if (granted) healthConnectEnabled.setChecked(true);
            Toast.makeText(
                    this,
                    granted
                            ? "Schreibrechte für die ausgewählten Werte wurden erlaubt."
                            : "Nicht alle Schreibrechte für die ausgewählten Werte wurden erlaubt.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void saveAndStart() {
        if (!hasBluetoothPermissions()) {
            requestNeededPermissions();
            Toast.makeText(this, "Bitte zuerst Bluetooth erlauben.", Toast.LENGTH_LONG).show();
            return;
        }
        String mac = macAddress.getText().toString().trim().toUpperCase(Locale.ROOT);
        String key = bindKey.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (!MAC_PATTERN.matcher(mac).matches()) {
            Toast.makeText(this, "Ungültige MAC-Adresse.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            Toast.makeText(this,
                    "Der Bind-Key muss aus genau 32 Hex-Zeichen bestehen.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (openScaleAuthority == null
                || users.isEmpty()
                || userSpinner.getSelectedItemPosition() < 0) {
            Toast.makeText(this,
                    "Bitte zuerst openScale verbinden und einen Benutzer auswählen.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        HealthConnectSelection healthSelection = healthConnectSelectionFromUi();
        if (healthConnectEnabled.isChecked() && healthSelection.count() == 0) {
            Toast.makeText(this,
                    "Bitte mindestens einen Health-Connect-Wert auswählen.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (healthConnectEnabled.isChecked()
                && !HealthConnectSupport.hasWritePermissions(this, healthSelection)) {
            requestHealthConnectPermissions();
            Toast.makeText(this,
                    "Bitte zuerst die Schreibrechte für die ausgewählten Werte erlauben.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        int currentAge = BirthDateUtils.ageToday(selectedBirthDate);
        if (currentAge < 18 || currentAge > 120) {
            Toast.makeText(this,
                    "Bitte einen gültigen Geburtstag wählen. Für die Körperanalyse gilt 18–120 Jahre.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        float parsedHeight;
        try {
            parsedHeight = Float.parseFloat(
                    heightCm.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Bitte die Größe korrekt eintragen.", Toast.LENGTH_LONG).show();
            return;
        }
        if (parsedHeight < 100f || parsedHeight > 230f) {
            Toast.makeText(this,
                    "Für die Körperanalyse muss die Größe 100–230 cm betragen.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        OpenScaleProvider.User user = users.get(userSpinner.getSelectedItemPosition());
        SharedPreferences.Editor editor = getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putString("mac", mac)
                .putString("bind_key", key)
                .putString("openscale_authority", openScaleAuthority)
                .putLong("openscale_user_id", user.id)
                .putInt("openscale_api_version", openScaleMeta.apiVersion)
                .putString("birth_date", BirthDateUtils.toIso(selectedBirthDate))
                .remove("age")
                .putFloat("height_cm", parsedHeight)
                .putInt("sex", sexMale.isChecked() ? 1 : 0)
                .putBoolean("autoStart", autoStart.isChecked())
                .putBoolean("health_connect_enabled", healthConnectEnabled.isChecked())
                .putBoolean("diagnostic_logging", diagnosticLogging.isChecked());
        healthSelection.save(editor);
        editor.apply();

        EventLog.info(this,
                "Konfiguration gespeichert – openScale-Benutzer: " + user.name
                        + " | Health Connect "
                        + (healthConnectEnabled.isChecked()
                        ? "aktiv (" + healthSelection.summary() + ")"
                        : "aus"));
        EventLog.debug(this,
                "Provider-API " + openScaleMeta.apiVersion
                        + " | Alter aktuell " + currentAge
                        + " | Größe " + parsedHeight + " cm");
        if (!openScaleMeta.supportsGenericValues()) {
            EventLog.warning(this,
                    "Provider-API 1 speichert extern nur Gewicht, Fett, Wasser und Muskel.");
        }
        startForegroundService(new Intent(this, ScaleScanService.class));
        status.setText("Status: Überwachung angefordert");
    }

    private void requestHealthConnectPermissions() {
        if (!HealthConnectSupport.isSupported()) {
            Toast.makeText(this,
                    "Direkte Health-Connect-Übertragung benötigt Android 14 oder neuer.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        HealthConnectSelection selection = healthConnectSelectionFromUi();
        if (selection.count() == 0) {
            Toast.makeText(this,
                    "Bitte zuerst mindestens einen Wert auswählen.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        requestPermissions(HealthConnectSupport.permissionsFor(selection), REQ_HEALTH_CONNECT);
    }

    private void refreshHealthConnectStatus() {
        if (healthConnectStatus == null || healthConnectEnabled == null) return;
        android.view.View connectButton = findViewById(R.id.connectHealthConnect);
        if (!HealthConnectSupport.isSupported()) {
            healthConnectStatus.setText(
                    "Health Connect: nicht verfügbar – direkte Übertragung benötigt Android 14+");
            healthConnectEnabled.setChecked(false);
            healthConnectEnabled.setEnabled(false);
            connectButton.setEnabled(false);
            return;
        }

        healthConnectEnabled.setEnabled(true);
        connectButton.setEnabled(true);
        HealthConnectSelection selection = healthConnectSelectionFromUi();
        int selected = selection.count();
        if (selected == 0) {
            healthConnectStatus.setText("Health Connect: keine Werte ausgewählt");
            healthConnectEnabled.setChecked(false);
            return;
        }

        int granted = HealthConnectSupport.grantedWritePermissionCount(this, selection);
        if (granted == selected) {
            healthConnectStatus.setText(
                    "Health Connect bereit – " + selected + " ausgewählte Schreibrechte vorhanden");
        } else {
            healthConnectStatus.setText(
                    "Health Connect: " + granted + "/" + selected
                            + " Rechte für die ausgewählten Werte vorhanden");
            if (healthConnectEnabled.isChecked()) healthConnectEnabled.setChecked(false);
        }
    }

    private boolean hasBluetoothPermissions() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
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
