package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class PeerInboxDedupStore {
    private static final String PREFS =
            "peer_inbox_v1";

    private static final String KEY =
            "processed";

    private static final class Item {
        final String senderDeviceId;
        final String messageId;
        final long seenAtMs;

        Item(
                String senderDeviceId,
                String messageId,
                long seenAtMs) {
            this.senderDeviceId = senderDeviceId;
            this.messageId = messageId;
            this.seenAtMs = seenAtMs;
        }
    }

    private PeerInboxDedupStore() {}

    static boolean contains(
            Context context,
            String senderDeviceId,
            String messageId) {
        return contains(
                prefs(context),
                senderDeviceId,
                messageId);
    }

    static boolean contains(
            SharedPreferences preferences,
            String senderDeviceId,
            String messageId) {
        for (Item item :
                load(preferences)) {
            if (item.senderDeviceId.equals(
                            senderDeviceId)
                    && item.messageId.equals(
                            messageId)) {
                return true;
            }
        }

        return false;
    }

    static void mark(
            Context context,
            String senderDeviceId,
            String messageId) {
        mark(
                prefs(context),
                senderDeviceId,
                messageId);
    }

    static void mark(
            SharedPreferences preferences,
            String senderDeviceId,
            String messageId) {
        if (!PeerTrustStore.isValidDeviceId(
                        senderDeviceId)
                || messageId == null
                || messageId.isBlank()
                || messageId.length() > 200) {
            return;
        }

        List<Item> items =
                load(preferences);

        items.removeIf(
                item ->
                        item.senderDeviceId.equals(
                                senderDeviceId)
                                && item.messageId.equals(
                                messageId));

        items.add(
                new Item(
                        senderDeviceId,
                        messageId,
                        System.currentTimeMillis()));

        save(
                preferences,
                items);
    }

    static int removePeer(
            Context context,
            String peerDeviceId) {
        List<Item> items =
                load(context);

        int before =
                items.size();

        items.removeIf(
                item ->
                        item.senderDeviceId.equals(
                                peerDeviceId));

        int removed =
                before - items.size();

        if (removed > 0) {
            save(
                    context,
                    items);
        }

        return removed;
    }

    private static List<Item> load(
            Context context) {
        return load(
                prefs(context));
    }

    static List<Item> load(
            SharedPreferences preferences) {
        List<Item> result =
                new ArrayList<>();

        String encoded =
                preferences.getString(
                        KEY,
                        "");

        if (encoded == null
                || encoded.isBlank()) {
            return result;
        }

        try {
            JSONArray array =
                    new JSONArray(encoded);

            for (int index = 0;
                 index < array.length();
                 index++) {
                JSONObject object =
                        array.optJSONObject(index);

                if (object == null) {
                    continue;
                }

                String sender =
                        object.optString(
                                "senderDeviceId",
                                "");

                String messageId =
                        object.optString(
                                "messageId",
                                "");

                long seenAt =
                        object.optLong(
                                "seenAtMs",
                                0L);

                if (PeerTrustStore.isValidDeviceId(
                                sender)
                        && !messageId.isBlank()
                        && messageId.length() <= 200
                        && seenAt > 0L) {
                    result.add(
                            new Item(
                                    sender,
                                    messageId,
                                    seenAt));
                }
            }
        } catch (JSONException ignored) {
        }

        return result;
    }

    private static void save(
            Context context,
            List<Item> items) {
        save(
                prefs(context),
                items);
    }

    static void save(
            SharedPreferences preferences,
            List<Item> items) {
        JSONArray array =
                new JSONArray();

        for (Item item : items) {
            try {
                JSONObject object =
                        new JSONObject();

                object.put(
                        "senderDeviceId",
                        item.senderDeviceId);
                object.put(
                        "messageId",
                        item.messageId);
                object.put(
                        "seenAtMs",
                        item.seenAtMs);

                array.put(
                        object);
            } catch (JSONException ignored) {
            }
        }

        preferences
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
