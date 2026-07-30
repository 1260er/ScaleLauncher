package de.pritcloud.scalelauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        boolean enabled = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .getBoolean("autoStart", false);
        if (enabled) {
            try { context.startForegroundService(new Intent(context, ScaleScanService.class)); }
            catch (Exception ignored) { }
        }
    }
}
