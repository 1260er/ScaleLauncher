package de.pritcloud.scalelauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts monitoring after device boot or restores it after an app update. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();

        boolean autoStart =
                context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                        .getBoolean("autoStart", false);

        ServiceState.Snapshot previousState =
                ServiceState.read(context);

        if (!shouldStart(
                action,
                autoStart,
                previousState.mode)) {
            return;
        }

        ServiceState.starting(
                context,
                context.getString(R.string.boot_start_preparing));

        try {
            context.startForegroundService(
                    new Intent(context, ScaleScanService.class));

            EventLog.info(
                    context,
                    context.getString(R.string.boot_start_requested));
        } catch (RuntimeException e) {
            String detail = e.getMessage();

            if (detail == null || detail.isBlank()) {
                detail = e.getClass().getSimpleName();
            }

            ServiceState.error(
                    context,
                    context.getString(R.string.boot_start_failed));

            EventLog.error(
                    context,
                    context.getString(
                            R.string.boot_start_failed_detail,
                            detail));
        }
    }

    static boolean shouldStart(
            String action,
            boolean autoStart,
            ServiceState.Mode previousMode) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return autoStart;
        }

        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return previousMode == ServiceState.Mode.RUNNING
                    || previousMode == ServiceState.Mode.STARTING;
        }

        return false;
    }
}
