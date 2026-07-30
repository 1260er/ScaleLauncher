package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Small API-independent helper for Health Connect permission handling. */
final class HealthConnectSupport {
    static final String WRITE_WEIGHT = "android.permission.health.WRITE_WEIGHT";
    static final String WRITE_HEIGHT = "android.permission.health.WRITE_HEIGHT";
    static final String WRITE_BODY_FAT = "android.permission.health.WRITE_BODY_FAT";
    static final String WRITE_BODY_WATER_MASS = "android.permission.health.WRITE_BODY_WATER_MASS";
    static final String WRITE_BONE_MASS = "android.permission.health.WRITE_BONE_MASS";
    static final String WRITE_LEAN_BODY_MASS = "android.permission.health.WRITE_LEAN_BODY_MASS";
    static final String WRITE_BASAL_METABOLIC_RATE =
            "android.permission.health.WRITE_BASAL_METABOLIC_RATE";

    static final String[] WRITE_PERMISSIONS = {
            WRITE_WEIGHT,
            WRITE_HEIGHT,
            WRITE_BODY_FAT,
            WRITE_BODY_WATER_MASS,
            WRITE_BONE_MASS,
            WRITE_LEAN_BODY_MASS,
            WRITE_BASAL_METABOLIC_RATE
    };

    private HealthConnectSupport() {}

    static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 34;
    }

    static String[] permissionsFor(HealthConnectSelection selection) {
        Set<String> permissions = new LinkedHashSet<>();
        if (selection.weight || selection.bmi) permissions.add(WRITE_WEIGHT);
        if (selection.bmi) permissions.add(WRITE_HEIGHT);
        if (selection.bodyFat) permissions.add(WRITE_BODY_FAT);
        if (selection.bodyWater) permissions.add(WRITE_BODY_WATER_MASS);
        if (selection.boneMass) permissions.add(WRITE_BONE_MASS);
        if (selection.leanBodyMass) permissions.add(WRITE_LEAN_BODY_MASS);
        if (selection.basalMetabolicRate) permissions.add(WRITE_BASAL_METABOLIC_RATE);
        return permissions.toArray(new String[0]);
    }

    static int requiredPermissionCount(HealthConnectSelection selection) {
        return permissionsFor(selection).length;
    }

    static boolean hasWritePermissions(Context context, HealthConnectSelection selection) {
        if (!isSupported() || selection.count() == 0) return false;
        for (String permission : permissionsFor(selection)) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    static int grantedWritePermissionCount(Context context, HealthConnectSelection selection) {
        if (!isSupported()) return 0;
        int granted = 0;
        for (String permission : permissionsFor(selection)) {
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                granted++;
            }
        }
        return granted;
    }

    /** Compatibility helper for already-installed configurations. */
    static boolean hasAllWritePermissions(Context context) {
        return hasWritePermissions(context, new HealthConnectSelection(
                true, true, true, true, true, true, true));
    }
}
