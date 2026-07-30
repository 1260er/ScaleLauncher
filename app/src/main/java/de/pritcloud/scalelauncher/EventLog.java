package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Small persistent ring log. Detailed entries are stored only in diagnostic mode. */
final class EventLog {
    private static final String PREFS = "event_log";
    private static final String KEY = "lines";
    private static final String APP_PREFS = "prefs";
    private static final String PREF_DIAGNOSTIC = "diagnostic_logging";
    private static final int MAX_LINES = 150;
    private static final int MAX_CHARS = 48 * 1024;

    static void info(Context context, String message) {
        append(context, "INFO", message);
    }

    static void warning(Context context, String message) {
        append(context, "WARN", message);
    }

    static void error(Context context, String message) {
        append(context, "FEHLER", message);
    }

    static void debug(Context context, String message) {
        if (isDiagnosticEnabled(context)) {
            append(context, "DEBUG", message);
        }
    }

    /** Kept for older call sites; normal entries are important information. */
    static void add(Context context, String message) {
        info(context, message);
    }

    static boolean isDiagnosticEnabled(Context context) {
        return context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_DIAGNOSTIC, false);
    }

    static synchronized String read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY, "");
        if (stored == null || stored.isBlank()) return "Noch keine Ereignisse.";

        String pruned = prune(stored);
        if (!stored.equals(pruned)) prefs.edit().putString(KEY, pruned).apply();
        return pruned.isBlank() ? "Noch keine Ereignisse." : pruned;
    }

    static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    static String limitDescription() {
        return "maximal " + MAX_LINES + " Einträge / 48 KB";
    }

    private static synchronized void append(Context context, String level, String message) {
        if (message == null || message.isBlank()) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String old = prefs.getString(KEY, "");
        List<String> lines = new ArrayList<>();
        if (old != null && !old.isEmpty()) {
            for (String line : old.split("\\n")) {
                if (!line.isEmpty()) lines.add(line);
            }
        }

        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date());
        String cleanMessage = message.replace('\n', ' ').replace('\r', ' ').trim();
        lines.add(time + "  [" + level + "]  " + cleanMessage);

        while (lines.size() > MAX_LINES) lines.remove(0);
        while (joinedLength(lines) > MAX_CHARS && lines.size() > 1) lines.remove(0);

        prefs.edit().putString(KEY, String.join("\n", lines)).apply();
    }

    private static String prune(String stored) {
        List<String> lines = new ArrayList<>();
        for (String line : stored.split("\\n")) {
            if (!line.isEmpty()) lines.add(line);
        }
        while (lines.size() > MAX_LINES) lines.remove(0);
        while (joinedLength(lines) > MAX_CHARS && lines.size() > 1) lines.remove(0);
        return String.join("\n", lines);
    }

    private static int joinedLength(List<String> lines) {
        int size = Math.max(0, lines.size() - 1);
        for (String line : lines) size += line.length();
        return size;
    }

    private EventLog() {}
}
