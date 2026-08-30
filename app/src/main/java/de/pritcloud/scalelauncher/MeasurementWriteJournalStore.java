package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class MeasurementWriteJournalStore {
    private static final String PREFS =
            "measurement_write_journal_v1";

    private static final String KEY =
            "entries";

    private static final int MAX_STORED_ITEMS = 1000;

    enum Status {
        MISSING,
        PREPARED,
        STORED,
        CONFLICT
    }

    private static final class Entry {
        final String measurementId;
        final String authority;
        final long userId;
        final long timestampMs;
        final Status status;
        final long updatedAtMs;

        Entry(
                String measurementId,
                String authority,
                long userId,
                long timestampMs,
                Status status,
                long updatedAtMs) {
            this.measurementId = measurementId;
            this.authority = authority;
            this.userId = userId;
            this.timestampMs = timestampMs;
            this.status = status;
            this.updatedAtMs = updatedAtMs;
        }

        boolean matches(
                String expectedAuthority,
                long expectedUserId,
                long expectedTimestampMs) {
            return authority.equals(expectedAuthority)
                    && userId == expectedUserId
                    && timestampMs == expectedTimestampMs;
        }
    }

    private MeasurementWriteJournalStore() {}

    static Status status(
            Context context,
            String measurementId,
            String authority,
            long userId,
            long timestampMs) {
        Entry entry =
                find(
                        context,
                        measurementId);

        if (entry == null) {
            return Status.MISSING;
        }

        return entry.matches(
                        authority,
                        userId,
                        timestampMs)
                ? entry.status
                : Status.CONFLICT;
    }

    static boolean prepare(
            Context context,
            String measurementId,
            String authority,
            long userId,
            long timestampMs) {
        return writeState(
                context,
                measurementId,
                authority,
                userId,
                timestampMs,
                Status.PREPARED);
    }

    static boolean markStored(
            Context context,
            String measurementId,
            String authority,
            long userId,
            long timestampMs) {
        return writeState(
                context,
                measurementId,
                authority,
                userId,
                timestampMs,
                Status.STORED);
    }

    private static boolean writeState(
            Context context,
            String measurementId,
            String authority,
            long userId,
            long timestampMs,
            Status newStatus) {
        if (measurementId == null
                || measurementId.isBlank()
                || measurementId.length() > 200
                || authority == null
                || authority.isBlank()
                || userId < 0L
                || timestampMs <= 0L
                || newStatus == null) {
            return false;
        }

        List<Entry> entries =
                load(
                        context);

        for (Entry entry : entries) {
            if (!entry.measurementId.equals(
                    measurementId)) {
                continue;
            }

            if (!entry.matches(
                    authority,
                    userId,
                    timestampMs)) {
                return false;
            }

            if (entry.status == Status.STORED
                    && newStatus == Status.PREPARED) {
                return true;
            }
        }

        entries.removeIf(
                entry ->
                        entry.measurementId.equals(
                                measurementId));

        entries.add(
                new Entry(
                        measurementId,
                        authority,
                        userId,
                        timestampMs,
                        newStatus,
                        System.currentTimeMillis()));

        trimStored(
                entries);

        return save(
                context,
                entries);
    }

    private static Entry find(
            Context context,
            String measurementId) {
        if (measurementId == null
                || measurementId.isBlank()) {
            return null;
        }

        for (Entry entry :
                load(context)) {
            if (entry.measurementId.equals(
                    measurementId)) {
                return entry;
            }
        }

        return null;
    }

    private static void trimStored(
            List<Entry> entries) {
        int storedCount = 0;

        for (Entry entry : entries) {
            if (entry.status == Status.STORED) {
                storedCount++;
            }
        }

        while (storedCount > MAX_STORED_ITEMS) {
            boolean removed = false;

            for (int index = 0;
                 index < entries.size();
                 index++) {
                if (entries.get(index).status
                        == Status.STORED) {
                    entries.remove(index);
                    storedCount--;
                    removed = true;
                    break;
                }
            }

            if (!removed) {
                break;
            }
        }
    }

    private static List<Entry> load(
            Context context) {
        List<Entry> result =
                new ArrayList<>();

        String encoded =
                prefs(context).getString(
                        KEY,
                        "");

        if (encoded == null
                || encoded.isBlank()) {
            return result;
        }

        try {
            JSONArray array =
                    new JSONArray(
                            encoded);

            for (int index = 0;
                 index < array.length();
                 index++) {
                JSONObject object =
                        array.optJSONObject(
                                index);

                if (object == null) {
                    continue;
                }

                String measurementId =
                        object.optString(
                                "measurementId",
                                "");

                String authority =
                        object.optString(
                                "authority",
                                "");

                long userId =
                        object.optLong(
                                "userId",
                                -1L);

                long timestampMs =
                        object.optLong(
                                "timestampMs",
                                0L);

                long updatedAtMs =
                        object.optLong(
                                "updatedAtMs",
                                0L);

                Status status;

                try {
                    status =
                            Status.valueOf(
                                    object.optString(
                                            "status",
                                            ""));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }

                if (measurementId.isBlank()
                        || measurementId.length() > 200
                        || authority.isBlank()
                        || userId < 0L
                        || timestampMs <= 0L
                        || updatedAtMs <= 0L) {
                    continue;
                }

                result.add(
                        new Entry(
                                measurementId,
                                authority,
                                userId,
                                timestampMs,
                                status,
                                updatedAtMs));
            }
        } catch (JSONException ignored) {
        }

        return result;
    }

    private static boolean save(
            Context context,
            List<Entry> entries) {
        JSONArray array =
                new JSONArray();

        for (Entry entry : entries) {
            try {
                JSONObject object =
                        new JSONObject();

                object.put(
                        "measurementId",
                        entry.measurementId);
                object.put(
                        "authority",
                        entry.authority);
                object.put(
                        "userId",
                        entry.userId);
                object.put(
                        "timestampMs",
                        entry.timestampMs);
                object.put(
                        "status",
                        entry.status.name());
                object.put(
                        "updatedAtMs",
                        entry.updatedAtMs);

                array.put(
                        object);
            } catch (JSONException ignored) {
                return false;
            }
        }

        return prefs(context)
                .edit()
                .putString(
                        KEY,
                        array.toString())
                .commit();
    }

    private static SharedPreferences prefs(
            Context context) {
        return context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE);
    }
}
