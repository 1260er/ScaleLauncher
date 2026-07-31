package de.pritcloud.scalelauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts monitoring after device boot and after an app update. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        boolean enabled = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .getBoolean("autoStart", false);
        if (!enabled) return;

        ServiceState.starting(context, "Automatischer Start wird vorbereitet");
        try {
            context.startForegroundService(new Intent(context, ScaleScanService.class));
            EventLog.info(context, "Automatischer Start nach Neustart/App-Update angefordert");
        } catch (RuntimeException e) {
            String detail = e.getMessage();
            if (detail == null || detail.isBlank()) detail = e.getClass().getSimpleName();
            ServiceState.error(context, "Automatischer Start fehlgeschlagen");
            EventLog.error(context, "Automatischer Start fehlgeschlagen: " + detail);
        }
    }
}
