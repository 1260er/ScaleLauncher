package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Small API-independent helper for Health Connect permission handling. */
final class HealthConnectSupport {
    static final String WRITE_WEIGHT = "android.permission.health.WRITE_WEIGHT";
    static final String WRITE_BODY_FAT = "android.permission.health.WRITE_BODY_FAT";
    static final String WRITE_BODY_WATER_MASS = "android.permission.health.WRITE_BODY_WATER_MASS";
    static final String WRITE_BONE_MASS = "android.permission.health.WRITE_BONE_MASS";
    static final String WRITE_LEAN_BODY_MASS = "android.permission.health.WRITE_LEAN_BODY_MASS";
    static final String WRITE_BASAL_METABOLIC_RATE =
            "android.permission.health.WRITE_BASAL_METABOLIC_RATE";
    static final String WRITE_HEART_RATE = "android.permission.health.WRITE_HEART_RATE";

    static final String[] WRITE_PERMISSIONS = {
            WRITE_WEIGHT,
            WRITE_BODY_FAT,
            WRITE_BODY_WATER_MASS,
            WRITE_BONE_MASS,
            WRITE_LEAN_BODY_MASS,
            WRITE_BASAL_METABOLIC_RATE,
            WRITE_HEART_RATE
    };

    private HealthConnectSupport() {}

    static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 34;
    }

    static boolean hasAllWritePermissions(Context context) {
        if (!isSupported()) return false;
        for (String permission : WRITE_PERMISSIONS) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    static int grantedWritePermissionCount(Context context) {
        if (!isSupported()) return 0;
        int granted = 0;
        for (String permission : WRITE_PERMISSIONS) {
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                granted++;
            }
        }
        return granted;
    }
}
