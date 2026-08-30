package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/** Persisted single source of truth for the foreground service and the UI. */
final class ServiceState {
    private static final String PREFS = "service_state";
    private static final String KEY_MODE = "mode";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_HEARTBEAT = "heartbeat";
    private static final String KEY_SCAN_RUNNING = "scan_running";
    private static final String KEY_COLLECTOR_SOURCE = "collector_source";
    private static final String KEY_LAST_SCALE_SEEN = "last_scale_seen";
    private static final String KEY_LAST_SUCCESS = "last_success";
    private static final String KEY_LAST_FAILURE = "last_failure";

    static final long STALE_AFTER_MS = 45_000L;
    static final long SCALE_SEEN_RECENT_MS = 90_000L;

    enum Mode { STOPPED, STARTING, RUNNING, ERROR }

    enum CollectorSource { NONE, LOCAL, REMOTE }

    static final class Snapshot {
        final Mode mode;
        final String message;
        final long heartbeatMs;
        final boolean scanRunning;
        final CollectorSource collectorSource;
        final long lastScaleSeenMs;
        final long lastSuccessMs;
        final long lastFailureMs;

        Snapshot(Mode mode,
                 String message,
                 long heartbeatMs,
                 boolean scanRunning,
                 CollectorSource collectorSource,
                 long lastScaleSeenMs,
                 long lastSuccessMs,
                 long lastFailureMs) {
            this.mode = mode;
            this.message = message;
            this.heartbeatMs = heartbeatMs;
            this.scanRunning = scanRunning;
            this.collectorSource = collectorSource;
            this.lastScaleSeenMs = lastScaleSeenMs;
            this.lastSuccessMs = lastSuccessMs;
            this.lastFailureMs = lastFailureMs;
        }

        boolean isStale(long ignoredNowMs) {
            long nowUptimeMs =
                    SystemClock.uptimeMillis();

            return (mode == Mode.RUNNING || mode == Mode.STARTING)
                    && (heartbeatMs <= 0L
                        || heartbeatMs > nowUptimeMs
                        || nowUptimeMs - heartbeatMs > STALE_AFTER_MS);
        }
    }

    private ServiceState() {}

    static Snapshot read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Mode mode;
        try {
            mode = Mode.valueOf(prefs.getString(KEY_MODE, Mode.STOPPED.name()));
        } catch (IllegalArgumentException e) {
            mode = Mode.STOPPED;
        }
        CollectorSource collectorSource;
        try {
            collectorSource =
                    CollectorSource.valueOf(
                            prefs.getString(
                                    KEY_COLLECTOR_SOURCE,
                                    CollectorSource.NONE.name()));
        } catch (IllegalArgumentException e) {
            collectorSource =
                    CollectorSource.NONE;
        }

        return new Snapshot(
                mode,
                prefs.getString(KEY_MESSAGE, context.getString(R.string.service_state_stopped_default)),
                prefs.getLong(KEY_HEARTBEAT, 0L),
                prefs.getBoolean(KEY_SCAN_RUNNING, false),
                collectorSource,
                prefs.getLong(KEY_LAST_SCALE_SEEN, 0L),
                prefs.getLong(KEY_LAST_SUCCESS, 0L),
                prefs.getLong(KEY_LAST_FAILURE, 0L));
    }

    static void starting(Context context, String message) {
        writeMode(
                context,
                Mode.STARTING,
                message,
                false,
                CollectorSource.NONE);
    }

    static void running(
            Context context,
            String message,
            boolean scanRunning) {
        writeMode(
                context,
                Mode.RUNNING,
                message,
                scanRunning,
                null);
    }

    static void running(
            Context context,
            String message,
            boolean scanRunning,
            CollectorSource collectorSource) {
        writeMode(
                context,
                Mode.RUNNING,
                message,
                scanRunning,
                collectorSource);
    }

    static void error(Context context, String message) {
        writeMode(
                context,
                Mode.ERROR,
                message,
                false,
                CollectorSource.NONE);
    }

    static void stopped(Context context, String message) {
        writeMode(
                context,
                Mode.STOPPED,
                message,
                false,
                CollectorSource.NONE);
    }

    static void heartbeat(Context context,
                          boolean scanRunning,
                          String message) {
        heartbeat(context, scanRunning, message, false);
    }

    static void heartbeat(Context context,
                          boolean scanRunning,
                          String message,
                          boolean scaleConnected) {
        heartbeat(
                context,
                scanRunning,
                message,
                scaleConnected,
                null);
    }

    static void heartbeat(Context context,
                          boolean scanRunning,
                          String message,
                          boolean scaleConnected,
                          CollectorSource collectorSource) {
        long nowWallClockMs =
                System.currentTimeMillis();

        long nowUptimeMs =
                SystemClock.uptimeMillis();

        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putLong(KEY_HEARTBEAT, nowUptimeMs)
                        .putBoolean(KEY_SCAN_RUNNING, scanRunning);

        if (collectorSource != null) {
            editor.putString(
                    KEY_COLLECTOR_SOURCE,
                    collectorSource.name());
        }
        if (scaleConnected) {
            editor.putLong(KEY_LAST_SCALE_SEEN, nowWallClockMs);
        }
        if (message != null && !message.isBlank()) {
            editor.putString(KEY_MESSAGE, message);
        }
        editor.commit();
    }

    static void scaleSeen(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_SCALE_SEEN, System.currentTimeMillis())
                .putLong(KEY_HEARTBEAT, SystemClock.uptimeMillis())
                .commit();
    }

    static void measurementSucceeded(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                .putLong(KEY_HEARTBEAT, SystemClock.uptimeMillis())
                .commit();
    }

    static void measurementFailed(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_FAILURE, System.currentTimeMillis())
                .putLong(KEY_HEARTBEAT, SystemClock.uptimeMillis())
                .commit();
    }

    private static void writeMode(
            Context context,
            Mode mode,
            String message,
            boolean scanRunning) {
        writeMode(
                context,
                mode,
                message,
                scanRunning,
                null);
    }

    private static void writeMode(
            Context context,
            Mode mode,
            String message,
            boolean scanRunning,
            CollectorSource collectorSource) {
        SharedPreferences.Editor editor =
                context.getSharedPreferences(
                                PREFS,
                                Context.MODE_PRIVATE)
                        .edit()
                        .putString(
                                KEY_MODE,
                                mode.name())
                        .putString(
                                KEY_MESSAGE,
                                message == null ? "" : message)
                        .putLong(
                                KEY_HEARTBEAT,
                                SystemClock.uptimeMillis())
                        .putBoolean(
                                KEY_SCAN_RUNNING,
                                scanRunning);

        if (collectorSource != null) {
            editor.putString(
                    KEY_COLLECTOR_SOURCE,
                    collectorSource.name());
        }

        editor.commit();
    }
}
