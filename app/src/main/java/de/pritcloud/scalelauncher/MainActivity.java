package de.pritcloud.scalelauncher;

import android.Manifest;
import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
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
            refreshPending();
            refreshRuntimeStatus();
            refreshReliabilityRequirements();
            refreshHandler.postDelayed(this, 1_000L);
        }
    };

    private EditText macAddress;
    private EditText bindKey;
    private EditText birthDate;
    private EditText heightCm;
    private EditText referenceWeight;
    private EditText weightTolerance;
    private CheckBox profileEnabled;
    private CheckBox healthConnectProfile;
    private CheckBox autoStart;
    private CheckBox healthConnectEnabled;
    private CheckBox hcWeight;
    private CheckBox hcBodyFat;
    private CheckBox hcBodyWater;
    private CheckBox hcBoneMass;
    private CheckBox hcLeanBodyMass;
    private CheckBox hcBmr;
    private CheckBox hcBmi;
    private CheckBox diagnosticLogging;
    private RadioButton sexMale;
    private Spinner userSpinner;
    private Spinner pendingUserSpinner;
    private TextView status;
    private android.widget.ImageView scaleStatusImage;
    private TextView log;
    private TextView openScaleStatus;
    private TextView healthConnectStatus;
    private TextView profileStatus;
    private TextView pendingStatus;
    private TextView systemRequirementsStatus;

    private String openScaleAuthority;
    private OpenScaleProvider.Meta openScaleMeta = new OpenScaleProvider.Meta(1, -1);
    private List<OpenScaleProvider.User> users = new ArrayList<>();
    private List<UserProfile> profiles = new ArrayList<>();
    private List<UserProfile> pendingProfiles = new ArrayList<>();
    private List<PendingMeasurementStore.Item> pendingMeasurements = new ArrayList<>();
    private LocalDate selectedBirthDate;
    private boolean loadingProfile;
    private String pendingProfileSignature = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        View contentRoot = findViewById(android.R.id.content);
        contentRoot.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safeInsets = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(
                    safeInsets.left,
                    safeInsets.top,
                    safeInsets.right,
                    safeInsets.bottom);
            return insets;
        });

        androidx.drawerlayout.widget.DrawerLayout drawerLayout =
                findViewById(R.id.drawerLayout);
        findViewById(R.id.buttonOpenMenu).setOnClickListener(
                view -> drawerLayout.openDrawer(android.view.Gravity.START));

        View pageHome = findViewById(R.id.pageHome);
        View pageScale = findViewById(R.id.pageScale);
        View pagePermissions = findViewById(R.id.pagePermissions);
        findViewById(R.id.navScale).setOnClickListener(view -> {
            pageHome.setVisibility(View.GONE);
            pageScale.setVisibility(View.VISIBLE);
            drawerLayout.closeDrawer(android.view.Gravity.START);
        });

        findViewById(R.id.navPermissions).setOnClickListener(view -> {
            pageHome.setVisibility(View.GONE);
            pageScale.setVisibility(View.GONE);
            pagePermissions.setVisibility(View.VISIBLE);
            drawerLayout.closeDrawer(android.view.Gravity.START);
        });

        macAddress = findViewById(R.id.macAddress);
        bindKey = findViewById(R.id.bindKey);
        birthDate = findViewById(R.id.birthDate);
        heightCm = findViewById(R.id.heightCm);
        referenceWeight = findViewById(R.id.referenceWeight);
        weightTolerance = findViewById(R.id.weightTolerance);
        profileEnabled = findViewById(R.id.profileEnabled);
        healthConnectProfile = findViewById(R.id.healthConnectProfile);
        autoStart = findViewById(R.id.autoStart);
        healthConnectEnabled = findViewById(R.id.healthConnectEnabled);
        hcWeight = findViewById(R.id.hcWeight);
        hcBodyFat = findViewById(R.id.hcBodyFat);
        hcBodyWater = findViewById(R.id.hcBodyWater);
        hcBoneMass = findViewById(R.id.hcBoneMass);
        hcLeanBodyMass = findViewById(R.id.hcLeanBodyMass);
        hcBmr = findViewById(R.id.hcBmr);
        hcBmi = findViewById(R.id.hcBmi);
        diagnosticLogging = findViewById(R.id.diagnosticLogging);
        sexMale = findViewById(R.id.sexMale);
        userSpinner = findViewById(R.id.openScaleUser);
        pendingUserSpinner = findViewById(R.id.pendingUser);
        status = findViewById(R.id.status);
        scaleStatusImage = findViewById(R.id.scaleStatusImage);
        log = findViewById(R.id.log);
        openScaleStatus = findViewById(R.id.openScaleStatus);
        healthConnectStatus = findViewById(R.id.healthConnectStatus);
        profileStatus = findViewById(R.id.profileStatus);
        pendingStatus = findViewById(R.id.pendingStatus);
        systemRequirementsStatus = findViewById(R.id.systemRequirementsStatus);

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        macAddress.setText(prefs.getString("mac", ""));
        bindKey.setText(prefs.getString("bind_key", ""));
        autoStart.setChecked(prefs.getBoolean("autoStart", false));
        diagnosticLogging.setChecked(prefs.getBoolean("diagnostic_logging", false));

        HealthConnectSelection storedSelection = HealthConnectSelection.fromPreferences(prefs);
        applyHealthConnectSelection(storedSelection);
        healthConnectEnabled.setChecked(
                prefs.getBoolean("health_connect_enabled", false)
                        && HealthConnectSupport.hasWritePermissions(this, storedSelection));

        userSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                loadProfileForPosition(position);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
                clearProfileEditor();
            }
        });

        birthDate.setOnClickListener(v -> showBirthDatePicker());
        findViewById(R.id.scanDevice).setOnClickListener(v -> {
            if (hasBluetoothPermissions()) {
                startActivityForResult(new Intent(this, DeviceScanActivity.class), REQ_SCAN);
            } else {
                requestNeededPermissions();
            }
        });
        findViewById(R.id.loadOpenScaleUsers).setOnClickListener(v -> prepareOpenScaleAccess());
        findViewById(R.id.saveProfile).setOnClickListener(v -> saveCurrentProfile(true));
        findViewById(R.id.connectHealthConnect).setOnClickListener(v -> requestHealthConnectPermissions());
        findViewById(R.id.saveStart).setOnClickListener(v -> saveAndStart());
        findViewById(R.id.saveScale).setOnClickListener(v -> saveScaleSettings());
        findViewById(R.id.stop).setOnClickListener(v -> {
            ServiceState.stopped(this, "Vom Benutzer gestoppt");
            startService(new Intent(this, ScaleScanService.class)
                    .setAction(ScaleScanService.ACTION_STOP));
            refreshRuntimeStatus();
        });
        findViewById(R.id.assignPending).setOnClickListener(v -> assignPendingMeasurement());
        findViewById(R.id.discardPending).setOnClickListener(v -> discardPendingMeasurement());
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
        findViewById(R.id.notificationSettings).setOnClickListener(
                v -> PowerSettingsHelper.openNotificationSettings(this));
        findViewById(R.id.batteryOptimizationSettings).setOnClickListener(
                v -> PowerSettingsHelper.requestBatteryOptimizationException(this));
        findViewById(R.id.unusedAppSettings).setOnClickListener(
                v -> PowerSettingsHelper.openUnusedAppSettings(this));

        android.widget.CompoundButton.OnCheckedChangeListener selectionListener =
                (button, checked) -> refreshHealthConnectStatus();
        hcWeight.setOnCheckedChangeListener(selectionListener);
        hcBodyFat.setOnCheckedChangeListener(selectionListener);
        hcBodyWater.setOnCheckedChangeListener(selectionListener);
        hcBoneMass.setOnCheckedChangeListener(selectionListener);
        hcLeanBodyMass.setOnCheckedChangeListener(selectionListener);
        hcBmr.setOnCheckedChangeListener(selectionListener);
        hcBmi.setOnCheckedChangeListener(selectionListener);

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
        refreshPending();
        refreshRuntimeStatus();
        refreshReliabilityRequirements();
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
            profiles = new ArrayList<>();
            updateUserSpinner(-1L);
            refreshPending();
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
            SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
            profiles = UserProfileStore.synchronize(prefs, users);

            boolean changed = false;
            for (UserProfile profile : profiles) {
                if (profile.referenceWeightKg <= 0f) {
                    float average = OpenScaleProvider.readAverageRecentWeight(
                            this, openScaleAuthority, profile.userId, 5);
                    if (average > 0f) {
                        profile.referenceWeightKg = average;
                        changed = true;
                    }
                }
            }
            if (changed) UserProfileStore.save(prefs, profiles);

            long storedUser = prefs.getLong(
                    "profile_editor_user_id",
                    prefs.getLong("openscale_user_id", -1L));
            updateUserSpinner(storedUser);

            String apiStatus = openScaleMeta.supportsGenericValues()
                    ? "Provider-API " + openScaleMeta.apiVersion + ": vollständige Messwerte möglich"
                    : "Provider-API 1: extern nur Gewicht, Fett, Wasser und Muskel";
            openScaleStatus.setText(users.isEmpty()
                    ? "openScale gefunden, aber keine Benutzer verfügbar – " + apiStatus
                    : "openScale verbunden: " + users.size() + " Benutzer – " + apiStatus);
            updateProfileStatus();
            refreshPending();
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
        int selectedPosition = 0;
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i).id == selectedId) selectedPosition = i;
        }
        if (!users.isEmpty()) {
            userSpinner.setSelection(selectedPosition);
            loadProfileForPosition(selectedPosition);
        } else {
            clearProfileEditor();
        }
    }

    private void loadProfileForPosition(int position) {
        if (loadingProfile || position < 0 || position >= users.size()) return;
        OpenScaleProvider.User user = users.get(position);
        UserProfile profile = UserProfileStore.find(profiles, user.id);
        if (profile == null) return;

        loadingProfile = true;
        profileEnabled.setChecked(profile.enabled);
        selectedBirthDate = BirthDateUtils.parseIso(profile.birthDateIso);
        updateBirthDateText();
        heightCm.setText(profile.heightCm > 0f ? formatDecimal(profile.heightCm) : "");
        referenceWeight.setText(profile.referenceWeightKg > 0f
                ? formatDecimal(profile.referenceWeightKg)
                : "");
        weightTolerance.setText(formatDecimal(
                profile.toleranceKg > 0f ? profile.toleranceKg : UserProfile.DEFAULT_TOLERANCE_KG));
        if (profile.male) sexMale.setChecked(true);
        else ((RadioButton) findViewById(R.id.sexFemale)).setChecked(true);
        long healthUserId = getSharedPreferences("prefs", MODE_PRIVATE)
                .getLong("health_connect_user_id", -1L);
        healthConnectProfile.setChecked(healthUserId == profile.userId);
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putLong("profile_editor_user_id", profile.userId)
                .apply();
        loadingProfile = false;
        updateProfileStatus();
    }

    private void clearProfileEditor() {
        loadingProfile = true;
        profileEnabled.setChecked(false);
        healthConnectProfile.setChecked(false);
        selectedBirthDate = null;
        updateBirthDateText();
        heightCm.setText("");
        referenceWeight.setText("");
        weightTolerance.setText(formatDecimal(UserProfile.DEFAULT_TOLERANCE_KG));
        loadingProfile = false;
    }

    private boolean saveCurrentProfile(boolean showToast) {
        int position = userSpinner.getSelectedItemPosition();
        if (position < 0 || position >= users.size()) {
            if (showToast) Toast.makeText(this, "Kein openScale-Benutzer ausgewählt.", Toast.LENGTH_LONG).show();
            return false;
        }
        OpenScaleProvider.User user = users.get(position);
        UserProfile profile = UserProfileStore.find(profiles, user.id);
        if (profile == null) {
            profile = new UserProfile(user.id, user.name);
            profiles.add(profile);
        }

        boolean enabled = profileEnabled.isChecked();
        Float parsedHeight = parseOptionalDecimal(heightCm);
        Float parsedReference = parseOptionalDecimal(referenceWeight);
        Float parsedTolerance = parseOptionalDecimal(weightTolerance);
        int currentAge = BirthDateUtils.ageToday(selectedBirthDate);

        if (enabled) {
            if (currentAge < 18 || currentAge > 120) {
                Toast.makeText(this,
                        "Bitte für " + user.name + " einen gültigen Geburtstag wählen.",
                        Toast.LENGTH_LONG).show();
                return false;
            }
            if (parsedHeight == null || parsedHeight < 100f || parsedHeight > 230f) {
                Toast.makeText(this,
                        "Bitte für " + user.name + " eine Größe von 100–230 cm eintragen.",
                        Toast.LENGTH_LONG).show();
                return false;
            }
            if (parsedReference == null || parsedReference < 20f || parsedReference > 300f) {
                Toast.makeText(this,
                        "Bitte für " + user.name + " ein gültiges Referenzgewicht eintragen.",
                        Toast.LENGTH_LONG).show();
                return false;
            }
            if (parsedTolerance == null || parsedTolerance < 0.2f || parsedTolerance > 30f) {
                Toast.makeText(this,
                        "Die Gewichtstoleranz muss zwischen 0,2 und 30 kg liegen.",
                        Toast.LENGTH_LONG).show();
                return false;
            }
        }

        profile.name = user.name;
        profile.enabled = enabled;
        profile.birthDateIso = BirthDateUtils.toIso(selectedBirthDate);
        profile.heightCm = parsedHeight == null ? 0f : parsedHeight;
        profile.male = sexMale.isChecked();
        profile.referenceWeightKg = parsedReference == null ? 0f : parsedReference;
        profile.toleranceKg = parsedTolerance == null
                ? UserProfile.DEFAULT_TOLERANCE_KG
                : parsedTolerance;

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        long oldHealthUser = prefs.getLong("health_connect_user_id", -1L);
        if (healthConnectProfile.isChecked()) {
            editor.putLong("health_connect_user_id", profile.userId);
        } else if (oldHealthUser == profile.userId) {
            editor.remove("health_connect_user_id");
        }
        editor.putLong("profile_editor_user_id", profile.userId).apply();
        UserProfileStore.save(prefs, profiles);
        updateProfileStatus();
        refreshHealthConnectStatus();
        refreshPending();

        if (showToast) {
            Toast.makeText(this,
                    "Benutzerprofil " + user.name + " gespeichert.",
                    Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void updateProfileStatus() {
        int enabledCount = 0;
        for (UserProfile profile : profiles) if (profile.enabled) enabledCount++;
        long healthUserId = getSharedPreferences("prefs", MODE_PRIVATE)
                .getLong("health_connect_user_id", -1L);
        UserProfile healthProfile = UserProfileStore.find(profiles, healthUserId);
        String healthName = healthProfile == null ? "nicht festgelegt" : healthProfile.name;
        profileStatus.setText("Aktive Zuordnungsprofile: " + enabledCount
                + " | Health-Connect-Benutzer: " + healthName);
    }

    private void applyHealthConnectSelection(HealthConnectSelection selection) {
        hcWeight.setChecked(selection.weight);
        hcBodyFat.setChecked(selection.bodyFat);
        hcBodyWater.setChecked(selection.bodyWater);
        hcBoneMass.setChecked(selection.boneMass);
        hcLeanBodyMass.setChecked(selection.leanBodyMass);
        hcBmr.setChecked(selection.basalMetabolicRate);
        hcBmi.setChecked(selection.bmi);
    }

    private HealthConnectSelection healthConnectSelectionFromUi() {
        return new HealthConnectSelection(
                hcWeight.isChecked(),
                hcBodyFat.isChecked(),
                hcBodyWater.isChecked(),
                hcBoneMass.isChecked(),
                hcLeanBodyMass.isChecked(),
                hcBmr.isChecked(),
                hcBmi.isChecked());
    }

    @Override protected void onResume() {
        super.onResume();
        refreshHealthConnectStatus();
        refreshPending();
        refreshRuntimeStatus();
        refreshReliabilityRequirements();
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

    private void saveScaleSettings() {
        String mac = macAddress.getText().toString().trim().toUpperCase(Locale.ROOT);
        String key = bindKey.getText().toString().trim().toLowerCase(Locale.ROOT);

        if (!MAC_PATTERN.matcher(mac).matches()) {
            Toast.makeText(this, R.string.scale_error_invalid_mac, Toast.LENGTH_LONG).show();
            return;
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            Toast.makeText(this, R.string.scale_error_invalid_bind_key, Toast.LENGTH_LONG).show();
            return;
        }

        getSharedPreferences("prefs", MODE_PRIVATE)
                .edit()
                .putString("mac", mac)
                .putString("bind_key", key)
                .apply();

        macAddress.setText(mac);
        bindKey.setText(key);
        EventLog.info(this, getString(R.string.scale_settings_saved));
        Toast.makeText(this, R.string.scale_settings_saved, Toast.LENGTH_SHORT).show();

        findViewById(R.id.pageScale).setVisibility(View.GONE);
        findViewById(R.id.pageHome).setVisibility(View.VISIBLE);
    }

    private void saveAndStart() {
        if (!ensureReliabilityRequirements()) return;
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
        if (openScaleAuthority == null || users.isEmpty()) {
            Toast.makeText(this,
                    "Bitte zuerst openScale verbinden und Benutzer laden.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!openScaleMeta.supportsGenericValues()) {
            Toast.makeText(this,
                    "Für eine vollständige und überprüfbare Messung wird openScale Provider-API 2 benötigt.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!saveCurrentProfile(false)) return;

        profiles = UserProfileStore.load(getSharedPreferences("prefs", MODE_PRIVATE));
        List<UserProfile> enabledProfiles = UserProfileStore.enabled(profiles);
        if (enabledProfiles.isEmpty()) {
            Toast.makeText(this,
                    "Bitte mindestens ein Benutzerprofil für die automatische Zuordnung aktivieren.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        long now = System.currentTimeMillis();
        for (UserProfile profile : enabledProfiles) {
            if (!profile.hasValidBodyData(now) || !profile.hasValidMatchingData()) {
                Toast.makeText(this,
                        "Das Profil " + profile.name + " ist noch nicht vollständig.",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        HealthConnectSelection healthSelection = healthConnectSelectionFromUi();
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        long healthUserId = prefs.getLong("health_connect_user_id", -1L);
        if (healthConnectEnabled.isChecked()) {
            if (healthSelection.count() == 0) {
                Toast.makeText(this,
                        "Bitte mindestens einen Health-Connect-Wert auswählen.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            UserProfile healthProfile = UserProfileStore.find(enabledProfiles, healthUserId);
            if (healthProfile == null) {
                Toast.makeText(this,
                        "Bitte in einem aktiven Profil den Health-Connect-Hauptbenutzer festlegen.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (!HealthConnectSupport.hasWritePermissions(this, healthSelection)) {
                requestHealthConnectPermissions();
                Toast.makeText(this,
                        "Bitte zuerst die Schreibrechte für die ausgewählten Werte erlauben.",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        SharedPreferences.Editor editor = prefs.edit()
                .putString("mac", mac)
                .putString("bind_key", key)
                .putString("openscale_authority", openScaleAuthority)
                .putInt("openscale_api_version", openScaleMeta.apiVersion)
                .putBoolean("autoStart", autoStart.isChecked())
                .putBoolean("health_connect_enabled", healthConnectEnabled.isChecked())
                .putBoolean("diagnostic_logging", diagnosticLogging.isChecked());
        healthSelection.save(editor);
        editor.apply();

        EventLog.info(this,
                "Konfiguration gespeichert – " + enabledProfiles.size()
                        + " Benutzerprofile aktiv | Health Connect "
                        + (healthConnectEnabled.isChecked() ? "aktiv" : "aus"));
        EventLog.debug(this,
                "Provider-API " + openScaleMeta.apiVersion
                        + " | Zuordnungsvorsprung mindestens "
                        + UserMatcher.MINIMUM_LEAD_KG + " kg");
        ServiceState.starting(this, "Überwachung wird gestartet");
        startForegroundService(new Intent(this, ScaleScanService.class));
        refreshRuntimeStatus();
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
        View connectButton = findViewById(R.id.connectHealthConnect);
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

        int required = HealthConnectSupport.requiredPermissionCount(selection);
        int granted = HealthConnectSupport.grantedWritePermissionCount(this, selection);
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        long healthUserId = prefs.getLong("health_connect_user_id", -1L);
        UserProfile healthProfile = UserProfileStore.find(profiles, healthUserId);
        String userText = healthProfile == null
                ? "Hauptbenutzer fehlt"
                : "Benutzer " + healthProfile.name;
        if (granted == required) {
            healthConnectStatus.setText(
                    "Health Connect bereit – " + userText + ", " + selected
                            + " Werte ausgewählt");
        } else {
            healthConnectStatus.setText(
                    "Health Connect: " + granted + "/" + required
                            + " benötigte Rechte vorhanden – " + userText);
            if (healthConnectEnabled.isChecked()) healthConnectEnabled.setChecked(false);
        }
    }

    private void refreshPending() {
        if (pendingStatus == null) return;
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        pendingMeasurements = PendingMeasurementStore.load(prefs);
        profiles = profiles.isEmpty() ? UserProfileStore.load(prefs) : profiles;
        pendingProfiles = UserProfileStore.enabled(profiles);

        StringBuilder signatureBuilder = new StringBuilder();
        for (UserProfile profile : pendingProfiles) {
            signatureBuilder.append(profile.userId).append(':').append(profile.name).append('|');
        }
        String signature = signatureBuilder.toString();
        if (!signature.equals(pendingProfileSignature)) {
            int oldPosition = pendingUserSpinner.getSelectedItemPosition();
            ArrayAdapter<UserProfile> adapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_spinner_item,
                    pendingProfiles);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            pendingUserSpinner.setAdapter(adapter);
            if (oldPosition >= 0 && oldPosition < pendingProfiles.size()) {
                pendingUserSpinner.setSelection(oldPosition);
            }
            pendingProfileSignature = signature;
        }

        boolean available = !pendingMeasurements.isEmpty() && !pendingProfiles.isEmpty();
        findViewById(R.id.assignPending).setEnabled(available);
        findViewById(R.id.discardPending).setEnabled(!pendingMeasurements.isEmpty());
        pendingUserSpinner.setEnabled(available);

        if (pendingMeasurements.isEmpty()) {
            pendingStatus.setText("Keine offene Messung");
            return;
        }
        PendingMeasurementStore.Item item = pendingMeasurements.get(0);
        pendingStatus.setText(String.format(
                Locale.GERMANY,
                "%d offene Messung(en) – nächste: %.1f kg (%s)",
                pendingMeasurements.size(),
                item.weightKg,
                item.reason));
    }

    private void assignPendingMeasurement() {
        if (pendingMeasurements.isEmpty() || pendingProfiles.isEmpty()) return;
        int position = pendingUserSpinner.getSelectedItemPosition();
        if (position < 0 || position >= pendingProfiles.size()) return;
        PendingMeasurementStore.Item item = pendingMeasurements.get(0);
        UserProfile profile = pendingProfiles.get(position);
        Intent intent = new Intent(this, ScaleScanService.class)
                .setAction(ScaleScanService.ACTION_ASSIGN_PENDING)
                .putExtra(ScaleScanService.EXTRA_PENDING_ID, item.id)
                .putExtra(ScaleScanService.EXTRA_USER_ID, profile.userId);
        startForegroundService(intent);
        Toast.makeText(this,
                String.format(Locale.GERMANY, "%.1f kg wird %s zugeordnet.", item.weightKg, profile.name),
                Toast.LENGTH_SHORT).show();
    }

    private void discardPendingMeasurement() {
        if (pendingMeasurements.isEmpty()) return;
        PendingMeasurementStore.Item item = pendingMeasurements.get(0);
        PendingMeasurementStore.remove(getSharedPreferences("prefs", MODE_PRIVATE), item.id);
        startForegroundService(new Intent(this, ScaleScanService.class)
                .setAction(ScaleScanService.ACTION_REFRESH_PENDING));
        EventLog.info(this, String.format(
                Locale.GERMANY,
                "Nicht zugeordnete Messung %.1f kg verworfen",
                item.weightKg));
        refreshPending();
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

    private boolean ensureReliabilityRequirements() {
        if (!PowerSettingsHelper.isBatteryOptimizationDisabled(this)) {
            Toast.makeText(this,
                    "Bitte ScaleLauncher zuerst von der Akkuoptimierung ausnehmen.",
                    Toast.LENGTH_LONG).show();
            PowerSettingsHelper.requestBatteryOptimizationException(this);
            return false;
        }
        if (!PowerSettingsHelper.isUnusedAppManagementDisabled(this)) {
            Toast.makeText(this,
                    "Bitte 'App bei Nichtnutzung verwalten' für ScaleLauncher deaktivieren.",
                    Toast.LENGTH_LONG).show();
            PowerSettingsHelper.openUnusedAppSettings(this);
            return false;
        }
        if (!PowerSettingsHelper.areNotificationsUsable(this)) {
            Toast.makeText(this,
                    "Benachrichtigungen müssen erlaubt sein, damit Messfehler zuverlässig angezeigt werden.",
                    Toast.LENGTH_LONG).show();
            PowerSettingsHelper.openNotificationSettings(this);
            return false;
        }
        return true;
    }

    private void refreshReliabilityRequirements() {
        if (systemRequirementsStatus == null) return;
        boolean battery = PowerSettingsHelper.isBatteryOptimizationDisabled(this);
        boolean unused = PowerSettingsHelper.isUnusedAppManagementDisabled(this);
        boolean notifications = PowerSettingsHelper.areNotificationsUsable(this);
        systemRequirementsStatus.setText(
                (battery ? "✓" : "✗") + " Akkuoptimierung: "
                        + (battery ? "aus / uneingeschränkt" : "noch aktiv") + "\n"
                        + (unused ? "✓" : "✗") + " Verwaltung bei Nichtnutzung: "
                        + (unused ? "deaktiviert" : "noch aktiv") + "\n"
                        + (notifications ? "✓" : "✗") + " Benachrichtigungen: "
                        + (notifications ? "erlaubt" : "nicht vollständig erlaubt"));
        systemRequirementsStatus.setTextColor(
                getColor(R.color.ui_text_primary));
    }

    private void refreshRuntimeStatus() {
        if (status == null) return;
        long now = System.currentTimeMillis();
        ServiceState.Snapshot snapshot = ServiceState.read(this);

        scaleStatusImage.setImageResource(R.drawable.scale_disconnected);
        scaleStatusImage.setContentDescription(
                getString(R.string.status_scale_disconnected));

        if (snapshot.isStale(now)) {
            status.setText("Status: FEHLER – Dienst antwortet nicht");
            status.setTextColor(getColor(R.color.ui_text_primary));
            return;
        }

        StringBuilder text = new StringBuilder("Status: ");
        switch (snapshot.mode) {
            case RUNNING:
                scaleStatusImage.setImageResource(R.drawable.scale_connected);
                scaleStatusImage.setContentDescription(
                        getString(R.string.status_scale_connected));
                text.append(snapshot.scanRunning ? "AKTIV" : "WARTET");
                status.setTextColor(getColor(R.color.ui_text_primary));
                break;
            case STARTING:
                text.append("STARTET");
                status.setTextColor(getColor(R.color.ui_text_primary));
                break;
            case ERROR:
                text.append("FEHLER");
                status.setTextColor(getColor(R.color.ui_text_primary));
                break;
            case STOPPED:
            default:
                text.append("GESTOPPT");
                status.setTextColor(getColor(R.color.ui_text_primary));
                break;
        }
        if (snapshot.message != null && !snapshot.message.isBlank()) {
            text.append(" – ").append(snapshot.message);
        }
        if (snapshot.lastScaleSeenMs > 0L && snapshot.mode == ServiceState.Mode.RUNNING) {
            text.append("\n").append(getString(
                    R.string.status_scale_last_seen,
                    relativeTime(now - snapshot.lastScaleSeenMs)));
        }
        if (snapshot.lastSuccessMs > 0L) {
            text.append("\nLetzte erfolgreiche Messung: ")
                    .append(relativeTime(now - snapshot.lastSuccessMs));
        }
        if (snapshot.lastFailureMs > snapshot.lastSuccessMs) {
            text.append("\nLetzte Messung fehlgeschlagen: ")
                    .append(relativeTime(now - snapshot.lastFailureMs));
        }
        status.setText(text.toString());
    }

    private static String relativeTime(long ageMs) {
        if (ageMs < 0L) ageMs = 0L;
        long seconds = ageMs / 1_000L;
        if (seconds < 5L) return "gerade eben";
        if (seconds < 60L) return "vor " + seconds + " Sekunden";
        long minutes = seconds / 60L;
        if (minutes < 60L) return "vor " + minutes + " Minuten";
        long hours = minutes / 60L;
        if (hours < 24L) return "vor " + hours + " Stunden";
        return "vor " + (hours / 24L) + " Tagen";
    }

    private Float parseOptionalDecimal(EditText input) {
        String text = input.getText().toString().trim().replace(',', '.');
        if (text.isEmpty()) return null;
        try {
            float value = Float.parseFloat(text);
            return Float.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatDecimal(float value) {
        return String.format(Locale.GERMANY, "%.1f", value);
    }

    private void refreshLog() {
        log.setText(EventLog.read(this));
    }
}
