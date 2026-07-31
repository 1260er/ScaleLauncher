package de.pritcloud.scalelauncher;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/** Checks and opens the two Android settings required for dependable background operation. */
final class PowerSettingsHelper {
    private PowerSettingsHelper() {}

    static boolean isBatteryOptimizationDisabled(Context context) {
        PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return manager != null && manager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    static boolean isUnusedAppManagementDisabled(Context context) {
        try {
            return context.getPackageManager().isAutoRevokeWhitelisted();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean areNotificationsUsable(Context context) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager != null && manager.areNotificationsEnabled();
    }

    static void requestBatteryOptimizationException(Context context) {
        Intent direct = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + context.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(direct);
        } catch (RuntimeException e) {
            Intent list = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(list);
        }
    }

    static void openUnusedAppSettings(Context context) {
        Intent direct = new Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS)
                .setData(Uri.parse("package:" + context.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(direct);
        } catch (RuntimeException e) {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + context.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(details);
        }
    }

    static void openNotificationSettings(Context context) {
        context.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
