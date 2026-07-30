package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class EventLog {
    private static final String PREFS = "event_log";
    private static final String KEY = "lines";
    private static final int MAX_LINES = 250;

    static synchronized void add(Context context, String message) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String old = prefs.getString(KEY, "");
        List<String> lines = new ArrayList<>();
        if (!old.isEmpty()) {
            for (String line : old.split("\\n")) if (!line.isEmpty()) lines.add(line);
        }
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        lines.add(time + "  " + message);
        while (lines.size() > MAX_LINES) lines.remove(0);
        prefs.edit().putString(KEY, String.join("\n", lines)).apply();
    }

    static String read(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "Noch keine Ereignisse.");
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    private EventLog() {}
}
