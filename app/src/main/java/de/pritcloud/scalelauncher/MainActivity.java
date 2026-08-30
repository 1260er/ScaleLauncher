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
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[0-9a-fA-F]{24}$");

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refreshLog();
            refreshPending();
            refreshRuntimeStatus();
            refreshReliabilityRequirements();
            refreshHomeUserSummary();
            refreshHandler.postDelayed(this, 1_000L);
        }
    };

    private EditText macAddress;
    private EditText loginToken;
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
    private List<PendingCandidate> pendingCandidates = new ArrayList<>();
    private List<PendingMeasurementStore.Item> pendingMeasurements = new ArrayList<>();
    private List<RemotePendingMeasurementStore.Item> remotePendingMeasurements =
            new ArrayList<>();
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
                view -> drawerLayout.openDrawer(android.view.Gravity.END));

        View pageHome = findViewById(R.id.pageHome);
        View pageScale = findViewById(R.id.pageScale);
        View pagePermissions = findViewById(R.id.pagePermissions);
        View pageUsers = findViewById(R.id.pageUsers);
        View pageUserDetail = findViewById(R.id.pageUserDetail);
        View pageHealthConnect = findViewById(R.id.pageHealthConnect);
        View pageLog = findViewById(R.id.pageLog);
        findViewById(R.id.navScale).setOnClickListener(view -> {
            pageHome.setVisibility(View.GONE);
            pagePermissions.setVisibility(View.GONE);
            pageUsers.setVisibility(View.GONE);
            pageUserDetail.setVisibility(View.GONE);
            pageHealthConnect.setVisibility(View.GONE);
            pageLog.setVisibility(View.GONE);
            pageScale.setVisibility(View.VISIBLE);
            drawerLayout.closeDrawer(android.view.Gravity.END);
        });

        findViewById(R.id.navPermissions).setOnClickListener(view -> {
            pageHome.setVisibility(View.GONE);
            pageScale.setVisibility(View.GONE);
            pageUsers.setVisibility(View.GONE);
            pageUserDetail.setVisibility(View.GONE);
            pageHealthConnect.setVisibility(View.GONE);
            pageLog.setVisibility(View.GONE);
            pagePermissions.setVisibility(View.VISIBLE);
            drawerLayout.closeDrawer(android.view.Gravity.END);
        });

        findViewById(R.id.navUsers).setOnClickListener(view -> {
            refreshUserList();
            refreshInlinePeerSummary();
            pageHome.setVisibility(View.GONE);
            pageScale.setVisibility(View.GONE);
            pagePermissions.setVisibility(View.GONE);
            pageUserDetail.setVisibility(View.GONE);
            pageHealthConnect.setVisibility(View.GONE);
            pageLog.setVisibility(View.GONE);
            pageUsers.setVisibility(View.VISIBLE);
            drawerLayout.closeDrawer(android.view.Gravity.END);
        });

        findViewById(R.id.navHealthConnect).setOnClickListener(view -> {
            pageHome.setVisibility(View.GONE);
            pageScale.setVisibility(View.GONE);
            pagePermissions.setVisibility(View.GONE);
            pageUsers.setVisibility(View.GONE);
            pageUserDetail.setVisibility(View.GONE);
            pageLog.setVisibility(View.GONE);
            pageHealthConnect.setVisibility(View.VISIBLE);
            refreshHealthConnectStatus();
            drawerLayout.closeDrawer(android.view.Gravity.END);
        });

        findViewById(R.id.navLog).setOnClickListener(view -> {
            pageHome.setVisibility(View.GONE);
            pageScale.setVisibility(View.GONE);
            pagePermissions.setVisibility(View.GONE);
            pageUsers.setVisibility(View.GONE);
            pageUserDetail.setVisibility(View.GONE);
            pageHealthConnect.setVisibility(View.GONE);
            pageLog.setVisibility(View.VISIBLE);
            refreshLog();
            drawerLayout.closeDrawer(android.view.Gravity.END);
        });

        findViewById(R.id.navHelp).setOnClickListener(view -> {
            drawerLayout.closeDrawer(android.view.Gravity.END);
            openExternalLink(R.string.help_url, R.string.help_link_error);
        });

        findViewById(R.id.navSupport).setOnClickListener(view -> {
            drawerLayout.closeDrawer(android.view.Gravity.END);
            openExternalLink(R.string.support_url, R.string.support_link_error);
        });

        findViewById(R.id.backUsers).setOnClickListener(view -> {
            pageUsers.setVisibility(View.GONE);
            pageHome.setVisibility(View.VISIBLE);
        });

        findViewById(R.id.backLog).setOnClickListener(view -> {
            pageLog.setVisibility(View.GONE);
            pageHome.setVisibility(View.VISIBLE);
        });

        macAddress = findViewById(R.id.macAddress);
        loginToken = findViewById(R.id.loginToken);
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

        // Migration from the former passive BLE broadcast implementation.
        // The GATT collector authenticates with the login token only.
        if (prefs.contains("bind_key")) {
            prefs.edit().remove("bind_key").apply();
        }

        macAddress.setText(prefs.getString("mac", ""));
        loginToken.setText(prefs.getString("login_token", ""));
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
        findViewById(R.id.pairPhone).setOnClickListener(
                v -> startActivity(
                        new Intent(
                                this,
                                PeerPairingActivity.class)));

        findViewById(R.id.loadOpenScaleUsers).setOnClickListener(v -> prepareOpenScaleAccess());
        findViewById(R.id.saveProfile).setOnClickListener(view -> {
            if (!saveCurrentProfile(true)) return;

            refreshUserList();
            refreshInlinePeerSummary();
            findViewById(R.id.pageUserDetail).setVisibility(View.GONE);
            findViewById(R.id.pageUsers).setVisibility(View.VISIBLE);
        });
        findViewById(R.id.connectHealthConnect).setOnClickListener(
                v -> requestHealthConnectPermissions());
        findViewById(R.id.saveHealthConnect).setOnClickListener(
                v -> saveHealthConnectSettings());
        findViewById(R.id.saveStart).setOnClickListener(v -> saveAndStart());
        findViewById(R.id.saveScale).setOnClickListener(v -> saveScaleSettings());
        findViewById(R.id.savePermissions).setOnClickListener(
                v -> savePermissionSettings());
        findViewById(R.id.stop).setOnClickListener(v -> {
            ServiceState.stopped(this, getString(R.string.service_stopped_by_user));
            startService(new Intent(this, ScaleScanService.class)
                    .setAction(ScaleScanService.ACTION_STOP));
            refreshRuntimeStatus();
        });
        findViewById(R.id.assignPending).setOnClickListener(v -> assignPendingMeasurement());
        findViewById(R.id.rejectPending).setOnClickListener(v -> rejectPendingMeasurement());
        findViewById(R.id.refreshLog).setOnClickListener(v -> refreshLog());
        findViewById(R.id.copyLog).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.clipboard_log_label),
                    EventLog.read(this)));
            LoggedToast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
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

        healthConnectEnabled.setOnCheckedChangeListener((button, enabled) ->
                refreshHealthConnectStatus());

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
            EventLog.info(this, getString(enabled
                    ? R.string.log_diagnostic_started
                    : R.string.log_diagnostic_stopped));
            refreshLog();
        });

        TextView logInfo = findViewById(R.id.logInfo);
        logInfo.setText(getString(
                R.string.log_info_limits,
                EventLog.limitDescription(this)));

        requestNeededPermissions();
        refreshLog();
        refreshPending();
        refreshRuntimeStatus();
        refreshReliabilityRequirements();
        prepareOpenScaleAccess();
        refreshHealthConnectStatus();
    }

    private void openExternalLink(int urlResource, int errorResource) {
        Intent intent = new Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse(getString(urlResource)));

        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException exception) {
            LoggedToast.makeText(
                    this,
                    errorResource,
                    Toast.LENGTH_LONG
            ).show();
        }
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
                ? getString(R.string.user_current_age, currentAge)
                : getString(R.string.user_birth_date_example));
    }

    private void prepareOpenScaleAccess() {
        openScaleAuthority = OpenScaleProvider.findAuthority(this);
        if (openScaleAuthority == null) {
            openScaleStatus.setText(
                    R.string.permissions_openscale_not_found);
            users = new ArrayList<>();
            profiles = new ArrayList<>();
            updateUserSpinner(-1L);
            refreshUserList();
            refreshHomeUserSummary();
            refreshPending();
            return;
        }
        String permission = OpenScaleProvider.permissionForAuthority(openScaleAuthority);
        if (permission != null
                && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            openScaleStatus.setText(
                    R.string.permissions_openscale_access_missing);
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
            profiles = UserProfileStore.synchronize(
                    prefs,
                    users,
                    PeerTrustStore.localDeviceId(this));

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
            refreshUserList();
            refreshHomeUserSummary();

            String openScaleLine = users.isEmpty()
                    ? getString(R.string.permissions_openscale_no_users)
                    : getString(
                            R.string.permissions_openscale_connected,
                            users.size());
            openScaleStatus.setText(openScaleLine);
            updateProfileStatus();
            refreshPending();
        } catch (SecurityException e) {
            openScaleStatus.setText(
                    R.string.permissions_openscale_access_denied);
        } catch (RuntimeException e) {
            openScaleStatus.setText(
                    R.string.permissions_openscale_query_failed);
        }
    }

    private void refreshUserList() {
        android.widget.LinearLayout container =
                findViewById(R.id.userListContainer);
        TextView emptyView = findViewById(R.id.usersEmpty);

        container.removeAllViews();

        if (users.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            return;
        }

        emptyView.setVisibility(View.GONE);

        for (OpenScaleProvider.User user : users) {
            View item = getLayoutInflater().inflate(
                    R.layout.item_user,
                    container,
                    false);

            TextView userName = item.findViewById(R.id.userName);
            userName.setText(user.name);

            View editButton = item.findViewById(R.id.editUser);
            editButton.setTag(user.id);
            editButton.setOnClickListener(view ->
                    openUserDetail((Long) view.getTag()));


            container.addView(item);
        }
    }

    private void openUserDetail(long userId) {
        profiles =
                UserProfileStore.load(
                        getSharedPreferences(
                                "prefs",
                                MODE_PRIVATE));

        for (int position = 0; position < users.size(); position++) {
            OpenScaleProvider.User user = users.get(position);
            if (user.id != userId) continue;

            userSpinner.setSelection(position);
            loadProfileForPosition(position);

            TextView title = findViewById(R.id.userDetailTitle);
            title.setText(user.name);

            findViewById(R.id.pageUsers).setVisibility(View.GONE);
            findViewById(R.id.pageUserDetail).setVisibility(View.VISIBLE);
            return;
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
            if (showToast) LoggedToast.makeText(
                    this,
                    R.string.profile_error_no_user,
                    Toast.LENGTH_LONG).show();
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
                LoggedToast.makeText(this,
                        getString(R.string.profile_error_birth_date, user.name),
                        Toast.LENGTH_LONG).show();
                return false;
            }
            if (parsedHeight == null || parsedHeight < 100f || parsedHeight > 230f) {
                LoggedToast.makeText(this,
                        getString(R.string.profile_error_height, user.name),
                        Toast.LENGTH_LONG).show();
                return false;
            }
            if (parsedReference == null || parsedReference < 20f || parsedReference > 300f) {
                LoggedToast.makeText(this,
                        getString(R.string.profile_error_reference_weight, user.name),
                        Toast.LENGTH_LONG).show();
                return false;
            }
            if (parsedTolerance == null || parsedTolerance < 0.2f || parsedTolerance > 30f) {
                LoggedToast.makeText(this,
                        getString(R.string.profile_error_tolerance),
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

        profile.ownerDeviceId =
                PeerTrustStore.localDeviceId(this);

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

        boolean profilePublished =
                HouseholdProfileSync.publishProfile(
                        this,
                        prefs,
                        profile.userId);

        if (profilePublished) {
            ServiceState.Snapshot serviceState =
                    ServiceState.read(
                            this);

            long now =
                    System.currentTimeMillis();

            if ((serviceState.mode
                            == ServiceState.Mode.RUNNING
                        || serviceState.mode
                            == ServiceState.Mode.STARTING)
                    && !serviceState.isStale(
                            now)) {
                startService(
                        new Intent(
                                this,
                                ScaleScanService.class)
                                .setAction(
                                        ScaleScanService.ACTION_SYNC_PEERS));
            }
        }

        profiles =
                UserProfileStore.load(prefs);

        updateProfileStatus();
        refreshHomeUserSummary();
        refreshHealthConnectStatus();
        refreshPending();

        if (showToast) {
            LoggedToast.makeText(this,
                    getString(R.string.user_profile_saved, user.name),
                    Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void refreshInlinePeerSummary() {
        TextView localInfo = findViewById(R.id.inlinePeerLocalInfo);
        TextView trustedInfo = findViewById(R.id.inlinePeerTrustedInfo);
        android.widget.LinearLayout trustedList =
                findViewById(R.id.inlinePeerTrustedList);

        if (localInfo == null
                || trustedInfo == null
                || trustedList == null) {
            return;
        }

        PeerEndpointInfo localEndpoint =
                PeerEndpointInfo.local(this);

        localInfo.setText(
                getString(
                        R.string.peer_local_device_details,
                        PeerPairingActivity.peerLabel(localEndpoint.deviceId),
                        inlineAssignedUsersText(localEndpoint.deviceId)));

        List<PeerTrustStore.Peer> peers =
                PeerTrustStore.load(this);

        trustedInfo.setVisibility(View.GONE);

        trustedList.removeAllViews();

        for (PeerTrustStore.Peer peer : peers) {
            View row =
                    getLayoutInflater().inflate(
                            R.layout.item_peer,
                            trustedList,
                            false);

            TextView info =
                    row.findViewById(R.id.peerItemInfo);

            android.widget.ImageButton deleteButton =
                    row.findViewById(R.id.peerDelete);

            info.setText(
                    peer.label
                            + (char) 10
                            + inlineAssignedUsersText(peer.deviceId));

            deleteButton.setOnClickListener(
                    view -> confirmRemoveInlinePeer(peer));

            trustedList.addView(row);
        }

    }

    private String inlineAssignedUsersText(
            String deviceId) {
        String localDeviceId =
                PeerTrustStore.localDeviceId(this);

        StringBuilder names =
                new StringBuilder();

        if (localDeviceId.equals(deviceId)) {
            List<UserProfile> storedProfiles =
                    UserProfileStore.load(
                            getSharedPreferences(
                                    "prefs",
                                    MODE_PRIVATE));

            for (UserProfile profile : storedProfiles) {
                if (!profile.enabled) {
                    continue;
                }

                if (names.length() > 0) {
                    names.append(", ");
                }

                names.append(profile.name);
            }
        } else {
            for (HouseholdProfile profile :
                    HouseholdProfileStore.load(this)) {
                if (!profile.active
                        || !deviceId.equals(profile.ownerDeviceId)) {
                    continue;
                }

                if (names.length() > 0) {
                    names.append(", ");
                }

                names.append(profile.name);
            }
        }

        if (names.length() == 0) {
            return getString(
                    R.string.peer_assigned_users_none);
        }

        return getString(
                R.string.peer_assigned_users,
                names.toString());
    }

    private void confirmRemoveInlinePeer(
            PeerTrustStore.Peer peer) {
        if (peer == null) {
            return;
        }

        String assignments =
                inlineAssignedUsersText(peer.deviceId);

        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.peer_remove_title)
                .setMessage(
                        getString(
                                R.string.peer_remove_message,
                                peer.label,
                                assignments))
                .setNegativeButton(
                        android.R.string.cancel,
                        null)
                .setPositiveButton(
                        R.string.peer_remove_confirm,
                        (dialog, which) -> {
                            String label = peer.label;

                            PeerTrustStore.remove(
                                    this,
                                    peer.deviceId);

                            ServiceState.Snapshot serviceState =
                                    ServiceState.read(
                                            this);

                            long now =
                                    System.currentTimeMillis();

                            if ((serviceState.mode
                                            == ServiceState.Mode.RUNNING
                                        || serviceState.mode
                                            == ServiceState.Mode.STARTING)
                                    && !serviceState.isStale(
                                            now)) {
                                startService(
                                        new Intent(
                                                this,
                                                ScaleScanService.class)
                                                .setAction(
                                                        ScaleScanService.ACTION_SYNC_PEERS));
                            }

                            String message =
                                    getString(
                                            R.string.peer_remove_success,
                                            label);

                            EventLog.info(this, message);

                            refreshInlinePeerSummary();
                            refreshHomeUserSummary();
                        })
                .show();
    }

    private void refreshHomeUserSummary() {
        TextView usersSummary = findViewById(R.id.homeUsersSummary);
        TextView usersList = findViewById(R.id.homeUsersList);
        TextView peerSyncStatus = findViewById(R.id.homePeerSyncStatus);
        TextView healthConnectState = findViewById(R.id.homeHealthConnectState);
        TextView healthConnectUser = findViewById(R.id.homeHealthConnectUser);

        List<OpenScaleProvider.User> localUsers =
                users == null
                        ? new ArrayList<>()
                        : users;

        String localDeviceId =
                PeerTrustStore.localDeviceId(
                        this);

        List<HouseholdProfile> remoteUsers =
                new ArrayList<>();

        for (HouseholdProfile profile :
                HouseholdProfileStore.active(
                        this)) {
            if (localDeviceId.equals(
                    profile.ownerDeviceId)) {
                continue;
            }

            if (!PeerTrustStore.isTrusted(
                    this,
                    profile.ownerDeviceId)) {
                continue;
            }

            remoteUsers.add(
                    profile);
        }

        int totalUsers =
                localUsers.size()
                        + remoteUsers.size();

        if (totalUsers == 0) {
            usersSummary.setText(
                    R.string.home_users_none);
            usersList.setText("");
        } else {
            usersSummary.setText(
                    getResources()
                            .getQuantityString(
                                    R.plurals.home_user_count,
                                    totalUsers,
                                    totalUsers));

            StringBuilder names =
                    new StringBuilder();

            for (OpenScaleProvider.User user :
                    localUsers) {
                if (names.length() > 0) {
                    names.append((char) 10);
                }

                names.append("• ")
                        .append(user.name)
                        .append(" ")
                        .append(
                                getString(
                                        R.string.home_user_local));
            }

            for (HouseholdProfile profile :
                    remoteUsers) {
                if (names.length() > 0) {
                    names.append((char) 10);
                }

                names.append("• ")
                        .append(profile.name)
                        .append(" ")
                        .append(
                                getString(
                                        R.string.home_user_remote));
            }

            usersList.setText(
                    names.toString());
        }

        int pendingSync =
                PeerOutboxStore.count(this);

        peerSyncStatus.setText(
                getResources()
                        .getQuantityString(
                                R.plurals.peer_outbox_pending,
                                pendingSync,
                                pendingSync));

        SharedPreferences prefs =
                getSharedPreferences(
                        "prefs",
                        MODE_PRIVATE);

        boolean healthConnectActive =
                prefs.getBoolean(
                        "health_connect_enabled",
                        false);

        healthConnectState.setText(
                healthConnectActive
                        ? R.string.home_health_connect_active
                        : R.string.home_health_connect_disabled);

        List<UserProfile> storedProfiles =
                UserProfileStore.load(
                        prefs);

        long healthUserId =
                prefs.getLong(
                        "health_connect_user_id",
                        -1L);

        UserProfile healthProfile =
                UserProfileStore.find(
                        storedProfiles,
                        healthUserId);

        healthConnectUser.setText(
                healthProfile == null
                        ? getString(
                                R.string.home_health_connect_user_none)
                        : "• " + healthProfile.name);
    }

    private void updateProfileStatus() {
        int enabledCount = 0;
        for (UserProfile profile : profiles) if (profile.enabled) enabledCount++;
        long healthUserId = getSharedPreferences("prefs", MODE_PRIVATE)
                .getLong("health_connect_user_id", -1L);
        UserProfile healthProfile = UserProfileStore.find(profiles, healthUserId);
        String healthName = healthProfile == null
                ? getString(R.string.profile_health_user_not_set)
                : healthProfile.name;
        profileStatus.setText(getString(
                R.string.profile_status_summary,
                enabledCount,
                healthName));
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
        ScaleScanService.clearTransientNotifications(this);
        refreshHealthConnectStatus();
        refreshInlinePeerSummary();
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
                EventLog.info(this, getString(R.string.log_scale_selected, mac));
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
                openScaleStatus.setText(
                        R.string.permissions_openscale_access_denied);
            }
            return;
        }
        if (requestCode == REQ_HEALTH_CONNECT) {
            HealthConnectSelection selection = healthConnectSelectionFromUi();
            refreshHealthConnectStatus();
            boolean granted = HealthConnectSupport.hasWritePermissions(this, selection);
            if (granted) healthConnectEnabled.setChecked(true);
            LoggedToast.makeText(
                    this,
                    granted
                            ? R.string.health_permissions_granted
                            : R.string.health_permissions_incomplete,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void savePermissionSettings() {
        getSharedPreferences("prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("autoStart", autoStart.isChecked())
                .apply();

        EventLog.info(this, getString(R.string.permissions_settings_saved));
        LoggedToast.makeText(
                this,
                R.string.permissions_settings_saved,
                Toast.LENGTH_SHORT).show();

        findViewById(R.id.pagePermissions).setVisibility(View.GONE);
        findViewById(R.id.pageHome).setVisibility(View.VISIBLE);
    }

    private void saveScaleSettings() {
        String mac = macAddress.getText().toString().trim().toUpperCase(Locale.ROOT);
        String token = loginToken.getText().toString().trim().toLowerCase(Locale.ROOT);

        if (!MAC_PATTERN.matcher(mac).matches()) {
            LoggedToast.makeText(this, R.string.scale_error_invalid_mac, Toast.LENGTH_LONG).show();
            return;
        }
        if (!TOKEN_PATTERN.matcher(token).matches()) {
            LoggedToast.makeText(this, R.string.scale_error_invalid_login_token, Toast.LENGTH_LONG).show();
            return;
        }

        getSharedPreferences("prefs", MODE_PRIVATE)
                .edit()
                .putString("mac", mac)
                .putString("login_token", token)
                .apply();

        macAddress.setText(mac);
        loginToken.setText(token);
        EventLog.info(this, getString(R.string.scale_settings_saved));
        LoggedToast.makeText(this, R.string.scale_settings_saved, Toast.LENGTH_SHORT).show();

        findViewById(R.id.pageScale).setVisibility(View.GONE);
        findViewById(R.id.pageHome).setVisibility(View.VISIBLE);
    }

    private void saveAndStart() {
        if (!ensureReliabilityRequirements()) return;
        if (!hasBluetoothPermissions()) {
            requestNeededPermissions();
            LoggedToast.makeText(
                    this,
                    R.string.start_error_bluetooth_permission,
                    Toast.LENGTH_LONG).show();
            return;
        }
        String mac = macAddress.getText().toString().trim().toUpperCase(Locale.ROOT);
        String token = loginToken.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (!MAC_PATTERN.matcher(mac).matches()) {
            LoggedToast.makeText(
                    this,
                    R.string.scale_error_invalid_mac,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!TOKEN_PATTERN.matcher(token).matches()) {
            LoggedToast.makeText(this,
                    getString(R.string.scale_error_invalid_login_token),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (openScaleAuthority == null || users.isEmpty()) {
            LoggedToast.makeText(this,
                    getString(R.string.start_error_openscale_missing),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!openScaleMeta.supportsGenericValues()) {
            LoggedToast.makeText(this,
                    getString(R.string.start_error_provider_api),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!saveCurrentProfile(false)) return;

        profiles = UserProfileStore.load(getSharedPreferences("prefs", MODE_PRIVATE));
        List<UserProfile> enabledProfiles = UserProfileStore.enabled(profiles);
        if (enabledProfiles.isEmpty()) {
            LoggedToast.makeText(this,
                    getString(R.string.start_error_no_active_profile),
                    Toast.LENGTH_LONG).show();
            return;
        }
        long now = System.currentTimeMillis();
        for (UserProfile profile : enabledProfiles) {
            if (!profile.hasValidBodyData(now) || !profile.hasValidMatchingData()) {
                LoggedToast.makeText(this,
                        getString(R.string.start_error_profile_incomplete, profile.name),
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        HealthConnectSelection healthSelection = healthConnectSelectionFromUi();
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        long healthUserId = prefs.getLong("health_connect_user_id", -1L);
        if (healthConnectEnabled.isChecked()) {
            if (healthSelection.count() == 0) {
                LoggedToast.makeText(this,
                        getString(R.string.health_connect_error_select_values),
                        Toast.LENGTH_LONG).show();
                return;
            }
            UserProfile healthProfile = UserProfileStore.find(enabledProfiles, healthUserId);
            if (healthProfile == null) {
                LoggedToast.makeText(this,
                        getString(R.string.health_connect_error_main_user),
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (!HealthConnectSupport.hasWritePermissions(this, healthSelection)) {
                requestHealthConnectPermissions();
                LoggedToast.makeText(this,
                        getString(R.string.health_connect_error_write_permissions),
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        SharedPreferences.Editor editor = prefs.edit()
                .putString("mac", mac)
                .putString("login_token", token)
                .putString("openscale_authority", openScaleAuthority)
                .putInt("openscale_api_version", openScaleMeta.apiVersion)
                .putBoolean("autoStart", autoStart.isChecked())
                .putBoolean("health_connect_enabled", healthConnectEnabled.isChecked())
                .putBoolean("diagnostic_logging", diagnosticLogging.isChecked());
        healthSelection.save(editor);
        editor.apply();

        EventLog.info(this, getString(
                R.string.log_configuration_saved,
                enabledProfiles.size(),
                getString(healthConnectEnabled.isChecked()
                        ? R.string.state_active
                        : R.string.state_inactive)));
        EventLog.debug(this, getString(
                R.string.log_provider_status,
                openScaleMeta.apiVersion));
        ServiceState.starting(this, getString(R.string.service_monitoring_starting));
        startForegroundService(new Intent(this, ScaleScanService.class));
        refreshRuntimeStatus();
    }

    private void saveHealthConnectSettings() {
        HealthConnectSelection selection = healthConnectSelectionFromUi();
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);

        if (healthConnectEnabled.isChecked()) {
            if (selection.count() == 0) {
                LoggedToast.makeText(
                        this,
                        getString(R.string.health_connect_error_select_values),
                        Toast.LENGTH_LONG).show();
                return;
            }

            List<UserProfile> currentProfiles = profiles.isEmpty()
                    ? UserProfileStore.load(prefs)
                    : profiles;
            long healthUserId = prefs.getLong("health_connect_user_id", -1L);
            UserProfile healthProfile =
                    UserProfileStore.find(currentProfiles, healthUserId);

            if (healthProfile == null) {
                LoggedToast.makeText(
                        this,
                        getString(R.string.health_connect_error_user_assignment),
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (!HealthConnectSupport.hasWritePermissions(this, selection)) {
                requestHealthConnectPermissions();
                LoggedToast.makeText(
                        this,
                        getString(R.string.health_connect_error_required_permissions),
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(
                        "health_connect_enabled",
                        healthConnectEnabled.isChecked());
        selection.save(editor);
        editor.apply();
        refreshHomeUserSummary();

        EventLog.info(this, getString(
                R.string.log_health_connect_saved,
                getString(healthConnectEnabled.isChecked()
                        ? R.string.state_enabled
                        : R.string.state_disabled),
                selection.count()));

        LoggedToast.makeText(
                this,
                R.string.health_connect_settings_saved,
                Toast.LENGTH_SHORT).show();

        findViewById(R.id.pageHealthConnect).setVisibility(View.GONE);
        findViewById(R.id.pageHome).setVisibility(View.VISIBLE);
    }

    private void requestHealthConnectPermissions() {
        if (!HealthConnectSupport.isSupported()) {
            LoggedToast.makeText(this,
                    getString(R.string.health_connect_requires_android_14),
                    Toast.LENGTH_LONG).show();
            return;
        }
        HealthConnectSelection selection = healthConnectSelectionFromUi();
        if (selection.count() == 0) {
            LoggedToast.makeText(this,
                    getString(R.string.health_connect_error_select_first),
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
                    R.string.health_connect_status_unavailable);
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
            healthConnectStatus.setText(R.string.health_connect_no_values_status);
            return;
        }

        int required = HealthConnectSupport.requiredPermissionCount(selection);
        int granted = HealthConnectSupport.grantedWritePermissionCount(this, selection);
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        long healthUserId = prefs.getLong("health_connect_user_id", -1L);
        UserProfile healthProfile = UserProfileStore.find(profiles, healthUserId);
        String userText = healthProfile == null
                ? getString(R.string.health_connect_main_user_missing)
                : getString(R.string.health_connect_user_name, healthProfile.name);
        if (granted == required) {
            healthConnectStatus.setText(getString(
                    R.string.health_connect_status_ready,
                    userText,
                    selected));
        } else {
            healthConnectStatus.setText(getString(
                    R.string.health_connect_status_permissions,
                    granted,
                    required,
                    userText));
        }
    }

    private void refreshPending() {
        if (pendingStatus == null) return;

        SharedPreferences prefs =
                getSharedPreferences(
                        "prefs",
                        MODE_PRIVATE);

        pendingMeasurements =
                PendingMeasurementStore.load(
                        prefs);

        remotePendingMeasurements =
                RemotePendingMeasurementStore.load(
                        this);

        pendingCandidates =
                new ArrayList<>();

        if (!pendingMeasurements.isEmpty()) {
            PendingMeasurementStore.Item item =
                    pendingMeasurements.get(0);

            String localDeviceId =
                    PeerTrustStore.localDeviceId(
                            this);

            List<UserProfile> localProfiles =
                    UserProfileStore.enabled(
                            UserProfileStore.load(
                                    prefs));

            List<String> remainingCandidateProfileIds =
                    item.remainingCandidateProfileIds();

            boolean localUsersRejected = false;

            for (String rejectedProfileId :
                    item.rejectedProfileIds) {
                HouseholdProfile rejectedProfile =
                        HouseholdProfileStore.find(
                                this,
                                rejectedProfileId);

                if (rejectedProfile != null
                        && localDeviceId.equals(
                                rejectedProfile.ownerDeviceId)) {
                    localUsersRejected = true;
                    break;
                }
            }

            /*
             * Weight matching decides automatic routing only. Once a
             * measurement needs a human decision, the collector must always
             * be able to assign it explicitly to any valid local user.
             *
             * Profiles that are still routing candidates keep the normal
             * candidate-selection path. Other local profiles use the existing
             * unrestricted manual assignment path.
             */
            for (UserProfile local :
                    localProfiles) {
                if (localUsersRejected
                        || !local.hasValidBodyData(
                                item.timestampMs)) {
                    continue;
                }

                HouseholdProfile household =
                        remainingCandidateProfileIds.contains(
                                local.householdProfileId)
                                ? HouseholdProfileStore.find(
                                        this,
                                        local.householdProfileId)
                                : null;

                boolean routingCandidate =
                        household != null
                                && localDeviceId.equals(
                                        household.ownerDeviceId);

                pendingCandidates.add(
                        new PendingCandidate(
                                local.householdProfileId,
                                routingCandidate
                                        ? household.ownerDeviceId
                                        : local.ownerDeviceId,
                                local.name,
                                local.userId,
                                !routingCandidate));
            }
        } else if (!remotePendingMeasurements.isEmpty()) {
            RemotePendingMeasurementStore.Item item =
                    remotePendingMeasurements.get(
                            0);

            String localDeviceId =
                    PeerTrustStore.localDeviceId(
                            this);

            List<UserProfile> localProfiles =
                    UserProfileStore.enabled(
                            UserProfileStore.load(
                                    prefs));

            for (String profileId :
                    item.candidateProfileIds) {
                UserProfile local =
                        UserProfileStore.findByHouseholdProfileId(
                                localProfiles,
                                profileId);

                if (local == null
                        || !localDeviceId.equals(
                                local.ownerDeviceId)
                        || !local.hasValidBodyData(
                                item.timestampMs)) {
                    continue;
                }

                pendingCandidates.add(
                        new PendingCandidate(
                                local.householdProfileId,
                                local.ownerDeviceId,
                                local.name,
                                local.userId,
                                false));
            }
        }

        StringBuilder signatureBuilder =
                new StringBuilder();

        if (!pendingMeasurements.isEmpty()) {
            signatureBuilder
                    .append("collector:")
                    .append(
                            pendingMeasurements.get(0).id)
                    .append('|');
        } else if (!remotePendingMeasurements.isEmpty()) {
            signatureBuilder
                    .append("remote:")
                    .append(
                            remotePendingMeasurements.get(0)
                                    .measurementId)
                    .append('|');
        }

        for (PendingCandidate candidate :
                pendingCandidates) {
            signatureBuilder
                    .append(candidate.profileId)
                    .append(':')
                    .append(candidate.ownerDeviceId)
                    .append(':')
                    .append(candidate.name)
                    .append('|');
        }

        String signature =
                signatureBuilder.toString();

        if (!signature.equals(
                pendingProfileSignature)) {
            int oldPosition =
                    pendingUserSpinner.getSelectedItemPosition();

            ArrayAdapter<PendingCandidate> adapter =
                    new ArrayAdapter<>(
                            this,
                            android.R.layout.simple_spinner_item,
                            pendingCandidates);

            adapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);

            pendingUserSpinner.setAdapter(
                    adapter);

            if (oldPosition >= 0
                    && oldPosition < pendingCandidates.size()) {
                pendingUserSpinner.setSelection(
                        oldPosition);
            }

            pendingProfileSignature =
                    signature;
        }

        boolean hasCollectorPending =
                !pendingMeasurements.isEmpty();

        boolean hasRemotePending =
                !remotePendingMeasurements.isEmpty();

        boolean hasPending =
                hasCollectorPending
                        || hasRemotePending;

        boolean resolved =
                hasCollectorPending
                        && pendingMeasurements.get(0)
                                .isResolved();

        boolean canAssign =
                hasPending
                        && !resolved
                        && !pendingCandidates.isEmpty();

        boolean canReject =
                hasCollectorPending
                        ? canAssign
                        : hasRemotePending;

        findViewById(
                R.id.assignPending)
                .setEnabled(
                        canAssign);

        findViewById(
                R.id.rejectPending)
                .setEnabled(
                        canReject);

        pendingUserSpinner.setEnabled(
                canAssign);

        if (!hasPending) {
            pendingStatus.setText(
                    R.string.pending_none);
            return;
        }

        if (!hasCollectorPending) {
            RemotePendingMeasurementStore.Item remote =
                    remotePendingMeasurements.get(
                            0);

            pendingStatus.setText(
                    getString(
                            R.string.pending_status_remote,
                            remote.weightKg));

            return;
        }

        PendingMeasurementStore.Item item =
                pendingMeasurements.get(0);

        if (item.isResolved()) {
            String selectedName =
                    item.selectedProfileId;

            for (HouseholdProfile profile :
                    HouseholdProfileStore.active(
                            this)) {
                if (item.selectedProfileId.equals(
                        profile.profileId)) {
                    selectedName =
                            profile.name;
                    break;
                }
            }

            pendingStatus.setText(
                    getString(
                            R.string.pending_status_resolved,
                            item.weightKg,
                            selectedName));
            return;
        }

        String reason =
                item.reason == null
                        || item.reason.isBlank()
                        ? getString(
                                R.string.pending_reason_ambiguous)
                        : item.reason;

        String candidateSummary =
                pendingMatchingCandidateSummary(
                        item);

        String detail =
                candidateSummary.isBlank()
                        ? reason
                        : candidateSummary;

        String countLine =
                getResources().getQuantityString(
                        R.plurals.pending_status_count,
                        pendingMeasurements.size(),
                        pendingMeasurements.size(),
                        item.weightKg);

        pendingStatus.setText(
                countLine
                        + "\n"
                        + detail);
    }

    private String pendingMatchingCandidateSummary(
            PendingMeasurementStore.Item item) {
        if (item == null
                || item.manualRescue) {
            return "";
        }

        List<String> remaining =
                item.remainingCandidateProfileIds();

        if (remaining.size() < 2) {
            return "";
        }

        String localDeviceId =
                PeerTrustStore.localDeviceId(
                        this);

        List<String> labels =
                new ArrayList<>();

        for (String profileId :
                remaining) {
            HouseholdProfile profile =
                    HouseholdProfileStore.find(
                            this,
                            profileId);

            if (profile == null) {
                continue;
            }

            String location =
                    getString(
                            localDeviceId.equals(
                                    profile.ownerDeviceId)
                                    ? R.string.home_user_local
                                    : R.string.home_user_remote);

            labels.add(
                    profile.name
                            + " "
                            + location);
        }

        if (labels.size() < 2) {
            return "";
        }

        StringBuilder names =
                new StringBuilder();

        for (int i = 0;
                i < labels.size();
                i++) {
            if (i > 0) {
                names.append(
                        i == labels.size() - 1
                                ? getString(
                                        R.string.pending_candidate_last_separator)
                                : getString(
                                        R.string.pending_candidate_separator));
            }

            names.append(
                    labels.get(i));
        }

        return getString(
                R.string.pending_candidates_match,
                names.toString());
    }

    private void assignPendingMeasurement() {
        if (pendingCandidates.isEmpty()
                || (pendingMeasurements.isEmpty()
                    && remotePendingMeasurements.isEmpty())) {
            return;
        }

        int position =
                pendingUserSpinner.getSelectedItemPosition();

        if (position < 0
                || position >= pendingCandidates.size()) {
            return;
        }

        PendingCandidate candidate =
                pendingCandidates.get(
                        position);

        if (!pendingMeasurements.isEmpty()) {
            PendingMeasurementStore.Item item =
                    pendingMeasurements.get(0);

            Intent intent =
                    new Intent(
                            this,
                            ScaleScanService.class)
                            .putExtra(
                                    ScaleScanService.EXTRA_PENDING_ID,
                                    item.id);

            if (candidate.unrestricted) {
                intent.setAction(
                        ScaleScanService.ACTION_ASSIGN_PENDING)
                        .putExtra(
                                ScaleScanService.EXTRA_USER_ID,
                                candidate.userId);
            } else {
                intent.setAction(
                        ScaleScanService.ACTION_SELECT_PENDING)
                        .putExtra(
                                ScaleScanService.EXTRA_PROFILE_ID,
                                candidate.profileId)
                        .putExtra(
                                ScaleScanService.EXTRA_OWNER_DEVICE_ID,
                                candidate.ownerDeviceId);
            }

            startForegroundService(
                    intent);

            LoggedToast.makeText(
                    this,
                    getString(
                            R.string.pending_assignment_toast,
                            item.weightKg,
                            candidate.name),
                    Toast.LENGTH_SHORT).show();

            return;
        }

        RemotePendingMeasurementStore.Item remote =
                remotePendingMeasurements.get(
                        0);

        startForegroundService(
                new Intent(
                        this,
                        ScaleScanService.class)
                        .setAction(
                                ScaleScanService.ACTION_ACCEPT_REMOTE_PENDING)
                        .putExtra(
                                ScaleScanService.EXTRA_PENDING_ID,
                                remote.measurementId)
                        .putExtra(
                                ScaleScanService.EXTRA_PROFILE_ID,
                                candidate.profileId));

        clearPendingDecisionUi();

        LoggedToast.makeText(
                this,
                getString(
                        R.string.pending_remote_accept_toast,
                        remote.weightKg,
                        candidate.name),
                Toast.LENGTH_SHORT).show();
    }

    private void rejectPendingMeasurement() {
        if (pendingMeasurements.isEmpty()
                && remotePendingMeasurements.isEmpty()) {
            return;
        }

        if (!pendingMeasurements.isEmpty()) {
            if (pendingCandidates.isEmpty()) {
                return;
            }

            PendingMeasurementStore.Item item =
                    pendingMeasurements.get(0);

            clearPendingDecisionUi();

            startForegroundService(
                    new Intent(
                            this,
                            ScaleScanService.class)
                            .setAction(
                                    ScaleScanService.ACTION_REJECT_PENDING)
                            .putExtra(
                                    ScaleScanService.EXTRA_PENDING_ID,
                                    item.id));

            LoggedToast.makeText(
                    this,
                    R.string.pending_not_my_device_toast,
                    Toast.LENGTH_SHORT).show();

            return;
        }

        RemotePendingMeasurementStore.Item remote =
                remotePendingMeasurements.get(
                        0);

        clearPendingDecisionUi();

        startForegroundService(
                new Intent(
                        this,
                        ScaleScanService.class)
                        .setAction(
                                ScaleScanService.ACTION_REJECT_REMOTE_PENDING)
                        .putExtra(
                                ScaleScanService.EXTRA_PENDING_ID,
                                remote.measurementId));

        LoggedToast.makeText(
                this,
                R.string.pending_not_my_device_toast,
                Toast.LENGTH_SHORT).show();
    }

    private void clearPendingDecisionUi() {
        pendingCandidates =
                new ArrayList<>();
        pendingProfileSignature =
                "";

        pendingUserSpinner.setAdapter(
                new ArrayAdapter<PendingCandidate>(
                        this,
                        android.R.layout.simple_spinner_item,
                        pendingCandidates));

        pendingUserSpinner.setEnabled(
                false);

        findViewById(
                R.id.assignPending)
                .setEnabled(
                        false);

        findViewById(
                R.id.rejectPending)
                .setEnabled(
                        false);
    }

    private final class PendingCandidate {
        final String profileId;
        final String ownerDeviceId;
        final String name;
        final long userId;
        final boolean unrestricted;

        PendingCandidate(
                String profileId,
                String ownerDeviceId,
                String name,
                long userId,
                boolean unrestricted) {
            this.profileId =
                    profileId;
            this.ownerDeviceId =
                    ownerDeviceId;
            this.name =
                    name == null
                            ? ""
                            : name;
            this.userId =
                    userId;
            this.unrestricted =
                    unrestricted;
        }

        @Override public String toString() {
            return name;
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

    private boolean ensureReliabilityRequirements() {
        if (!PowerSettingsHelper.isBatteryOptimizationDisabled(this)) {
            LoggedToast.makeText(this,
                    getString(R.string.reliability_battery_required),
                    Toast.LENGTH_LONG).show();
            PowerSettingsHelper.requestBatteryOptimizationException(this);
            return false;
        }
        if (!PowerSettingsHelper.isUnusedAppManagementDisabled(this)) {
            LoggedToast.makeText(this,
                    getString(R.string.reliability_unused_app_required),
                    Toast.LENGTH_LONG).show();
            PowerSettingsHelper.openUnusedAppSettings(this);
            return false;
        }
        if (!PowerSettingsHelper.areNotificationsUsable(this)) {
            LoggedToast.makeText(this,
                    getString(R.string.reliability_notifications_required),
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
                getString(battery
                        ? R.string.permissions_battery_ok
                        : R.string.permissions_battery_bad)
                        + "\n"
                        + getString(unused
                        ? R.string.permissions_unused_ok
                        : R.string.permissions_unused_bad)
                        + "\n"
                        + getString(notifications
                        ? R.string.permissions_notifications_ok
                        : R.string.permissions_notifications_bad));
        systemRequirementsStatus.setTextColor(
                getColor(R.color.ui_text_primary));
    }

    private void refreshRuntimeStatus() {
        if (status == null) return;
        long now = System.currentTimeMillis();
        ServiceState.Snapshot snapshot = ServiceState.read(this);

        boolean serviceActive =
                !snapshot.isStale(now)
                        && (snapshot.mode == ServiceState.Mode.STARTING
                            || snapshot.mode == ServiceState.Mode.RUNNING);

        findViewById(R.id.saveStart)
                .setEnabled(!serviceActive);

        scaleStatusImage.setImageResource(R.drawable.scale_disconnected);
        scaleStatusImage.setContentDescription(
                getString(R.string.status_scale_disconnected));

        if (snapshot.isStale(now)) {
            status.setText(R.string.status_service_unresponsive);
            status.setTextColor(getColor(R.color.ui_text_primary));
            return;
        }

        StringBuilder text =
                new StringBuilder(
                        getString(R.string.status_prefix))
                        .append(" ");

        String runtimeMessage =
                snapshot.message;

        boolean collectorAvailable =
                snapshot.collectorSource
                        != ServiceState.CollectorSource.NONE;

        switch (snapshot.mode) {
            case RUNNING:
                if (collectorAvailable) {
                    scaleStatusImage.setImageResource(
                            R.drawable.scale_connected);

                    scaleStatusImage.setContentDescription(
                            getString(
                                    snapshot.collectorSource
                                                    == ServiceState.CollectorSource.LOCAL
                                            ? R.string.status_collector_local
                                            : R.string.status_collector_remote));
                } else {
                    scaleStatusImage.setContentDescription(
                            getString(
                                    R.string.status_collector_unavailable));
                }

                text.append(
                        getString(
                                collectorAvailable
                                        ? R.string.status_active
                                        : R.string.status_waiting));

                if (snapshot.collectorSource
                        == ServiceState.CollectorSource.LOCAL) {
                    runtimeMessage =
                            getString(
                                    R.string.service_gatt_ready);
                } else if (snapshot.collectorSource
                        == ServiceState.CollectorSource.REMOTE) {
                    runtimeMessage =
                            getString(
                                    R.string.status_remote_collector_waiting);
                } else {
                    runtimeMessage =
                            getString(
                                    R.string.status_waiting_for_scale);
                }

                status.setTextColor(
                        getColor(R.color.ui_text_primary));
                break;

            case STARTING:
                text.append(getString(R.string.status_starting));
                status.setTextColor(getColor(R.color.ui_text_primary));
                break;

            case ERROR:
                text.append(getString(R.string.status_error));
                status.setTextColor(getColor(R.color.ui_text_primary));
                break;

            case STOPPED:
            default:
                text.append(getString(R.string.status_stopped));
                status.setTextColor(getColor(R.color.ui_text_primary));
                break;
        }

        if (runtimeMessage != null
                && !runtimeMessage.isBlank()) {
            text.append("\n")
                    .append(runtimeMessage);
        }

        if (snapshot.lastSuccessMs > 0L) {
            text.append("\n").append(getString(
                    R.string.status_last_success,
                    relativeTime(now - snapshot.lastSuccessMs)));
        }
        if (snapshot.lastFailureMs > snapshot.lastSuccessMs) {
            text.append("\n").append(getString(
                    R.string.status_last_failure,
                    relativeTime(now - snapshot.lastFailureMs)));
        }
        status.setText(text.toString());
    }

    private String relativeTime(long ageMs) {
        if (ageMs < 0L) ageMs = 0L;
        long seconds = ageMs / 1_000L;
        if (seconds < 5L) return getString(R.string.time_just_now);
        if (seconds < 60L) return getString(R.string.time_seconds_ago, seconds);
        long minutes = seconds / 60L;
        if (minutes < 60L) return getString(R.string.time_minutes_ago, minutes);
        long hours = minutes / 60L;
        if (hours < 24L) return getString(R.string.time_hours_ago, hours);
        return getString(R.string.time_days_ago, hours / 24L);
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
