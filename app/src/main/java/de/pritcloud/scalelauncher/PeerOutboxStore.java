package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class PeerOutboxStore {
    static final String KIND_PROFILE =
            "profile_upsert";

    static final String KIND_PROFILE_MANIFEST =
            "profile_manifest";

    static final String KIND_MEASUREMENT =
            "measurement";

    static final String KIND_CLAIM =
            "measurement_claim";

    static final String KIND_DECISION =
            "measurement_decision";

    static final String KIND_CLOSED =
            "measurement_closed";

    static final String KIND_COLLECTOR_STATUS =
            "collector_status";

    private static final String PREFS =
            "peer_outbox_v1";

    private static final String KEY =
            "items";


    static final class Item {
        final String messageId;
        final String peerDeviceId;
        final String kind;
        final String dedupKey;
        final String payload;
        final long createdAtMs;

        Item(
                String messageId,
                String peerDeviceId,
                String kind,
                String dedupKey,
                String payload,
                long createdAtMs) {
            this.messageId = messageId;
            this.peerDeviceId = peerDeviceId;
            this.kind = kind;
            this.dedupKey = dedupKey;
            this.payload = payload;
            this.createdAtMs = createdAtMs;
        }
    }

    private PeerOutboxStore() {}

    static void enqueueProfile(
            Context context,
            String peerDeviceId,
            PeerProfilePayload payload) {
        if (!PeerTrustStore.isValidDeviceId(
                        peerDeviceId)
                || payload == null
                || !payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid profile outbox item");
        }

        enqueue(
                context,
                new Item(
                        payload.messageId,
                        peerDeviceId,
                        KIND_PROFILE,
                        payload.profile.profileId,
                        payload.encode(),
                        System.currentTimeMillis()),
                true);
    }

    static void enqueueProfileManifest(
            Context context,
            String peerDeviceId,
            PeerProfileManifestPayload payload) {
        if (!PeerTrustStore.isValidDeviceId(
                        peerDeviceId)
                || payload == null
                || !payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid profile manifest outbox item");
        }

        enqueue(
                context,
                new Item(
                        payload.messageId,
                        peerDeviceId,
                        KIND_PROFILE_MANIFEST,
                        "owner-manifest",
                        payload.encode(),
                        System.currentTimeMillis()),
                true);
    }

    static void enqueueMeasurement(
            Context context,
            String peerDeviceId,
            PeerMeasurementPayload payload) {
        if (!PeerTrustStore.isValidDeviceId(
                        peerDeviceId)
                || payload == null
                || !payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid measurement outbox item");
        }

        enqueue(
                context,
                new Item(
                        payload.transportMessageId(),
                        peerDeviceId,
                        KIND_MEASUREMENT,
                        payload.measurementId,
                        payload.encode(),
                        System.currentTimeMillis()),
                false);
    }

    static void enqueueClaim(
            Context context,
            String peerDeviceId,
            PeerClaimPayload payload) {
        if (!PeerTrustStore.isValidDeviceId(
                        peerDeviceId)
                || payload == null
                || !payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid claim outbox item");
        }

        enqueue(
                context,
                new Item(
                        payload.messageId,
                        peerDeviceId,
                        KIND_CLAIM,
                        payload.measurementId,
                        payload.encode(),
                        System.currentTimeMillis()),
                true);
    }

    static void enqueueDecision(
            Context context,
            String peerDeviceId,
            PeerMeasurementDecisionPayload payload) {
        if (!PeerTrustStore.isValidDeviceId(
                        peerDeviceId)
                || payload == null
                || !payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid measurement decision outbox item");
        }

        enqueue(
                context,
                new Item(
                        payload.messageId,
                        peerDeviceId,
                        KIND_DECISION,
                        payload.measurementId
                                + ":"
                                + payload.profileId,
                        payload.encode(),
                        System.currentTimeMillis()),
                true);
    }

    static void enqueueCollectorStatus(
            Context context,
            String peerDeviceId,
            PeerCollectorStatusPayload payload) {
        if (!PeerTrustStore.isValidDeviceId(
                        peerDeviceId)
                || payload == null
                || !payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid collector status outbox item");
        }

        enqueue(
                context,
                new Item(
                        payload.messageId,
                        peerDeviceId,
                        KIND_COLLECTOR_STATUS,
                        "collector_status",
                        payload.encode(),
                        System.currentTimeMillis()),
                true);
    }

    static void enqueueClosed(
            Context context,
            String peerDeviceId,
            PeerMeasurementClosedPayload payload) {
        if (!PeerTrustStore.isValidDeviceId(
                        peerDeviceId)
                || payload == null
                || !payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid closed measurement outbox item");
        }

        enqueue(
                context,
                new Item(
                        payload.messageId,
                        peerDeviceId,
                        KIND_CLOSED,
                        payload.measurementId,
                        payload.encode(),
                        System.currentTimeMillis()),
                true);
    }

    static List<Item> load(
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

                Item item =
                        new Item(
                                object.optString(
                                        "messageId",
                                        ""),
                                object.optString(
                                        "peerDeviceId",
                                        ""),
                                object.optString(
                                        "kind",
                                        ""),
                                object.optString(
                                        "dedupKey",
                                        ""),
                                object.optString(
                                        "payload",
                                        ""),
                                object.optLong(
                                        "createdAtMs",
                                        0L));

                if (isValid(item)) {
                    result.add(item);
                }
            }
        } catch (JSONException ignored) {
        }

        return result;
    }

    static List<Item> forPeer(
            Context context,
            String peerDeviceId) {
        List<Item> result =
                new ArrayList<>();

        for (Item item :
                load(context)) {
            if (item.peerDeviceId.equals(
                    peerDeviceId)) {
                result.add(item);
            }
        }

        return result;
    }

    static boolean remove(
            Context context,
            String peerDeviceId,
            String messageId) {
        List<Item> items =
                load(context);

        boolean removed =
                items.removeIf(
                        item ->
                                item.peerDeviceId.equals(
                                        peerDeviceId)
                                        && item.messageId.equals(
                                        messageId));

        if (removed) {
            save(
                    context,
                    items);
        }

        return removed;
    }

    static int count(
            Context context) {
        return load(context).size();
    }

    static int removeMeasurement(
            Context context,
            String measurementId) {
        if (measurementId == null
                || measurementId.isBlank()) {
            return 0;
        }

        List<Item> items =
                load(context);

        int before =
                items.size();

        items.removeIf(
                item ->
                        measurementId.equals(
                                item.dedupKey)
                                || item.dedupKey.startsWith(
                                        measurementId + ":"));

        int removed =
                before - items.size();

        if (removed > 0) {
            save(
                    context,
                    items);
        }

        return removed;
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
                        item.peerDeviceId.equals(
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

    private static void enqueue(
            Context context,
            Item incoming,
            boolean coalesce) {
        enqueue(
                prefs(context),
                incoming,
                coalesce);
    }

    static void enqueue(
            SharedPreferences preferences,
            Item incoming,
            boolean coalesce) {
        List<Item> items =
                load(preferences);

        for (Item existing :
                items) {
            if (existing.peerDeviceId.equals(
                            incoming.peerDeviceId)
                    && existing.messageId.equals(
                            incoming.messageId)) {
                return;
            }
        }

        if (coalesce) {
            items.removeIf(
                    existing ->
                            existing.peerDeviceId.equals(
                                    incoming.peerDeviceId)
                                    && existing.kind.equals(
                                    incoming.kind)
                                    && existing.dedupKey.equals(
                                    incoming.dedupKey));
        }

        items.add(
                incoming);

        save(
                preferences,
                items);
    }

    private static boolean isValid(
            Item item) {
        return item != null
                && item.messageId != null
                && !item.messageId.isBlank()
                && item.messageId.length() <= 200
                && PeerTrustStore.isValidDeviceId(
                        item.peerDeviceId)
                && (KIND_PROFILE.equals(
                        item.kind)
                    || KIND_PROFILE_MANIFEST.equals(
                        item.kind)
                    || KIND_MEASUREMENT.equals(
                        item.kind)
                    || KIND_CLAIM.equals(
                        item.kind)
                    || KIND_DECISION.equals(
                        item.kind)
                    || KIND_CLOSED.equals(
                        item.kind)
                    || KIND_COLLECTOR_STATUS.equals(
                        item.kind))
                && item.dedupKey != null
                && !item.dedupKey.isBlank()
                && item.payload != null
                && !item.payload.isBlank()
                && item.payload.length() <= 16384
                && item.createdAtMs > 0L;
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
                        "messageId",
                        item.messageId);
                object.put(
                        "peerDeviceId",
                        item.peerDeviceId);
                object.put(
                        "kind",
                        item.kind);
                object.put(
                        "dedupKey",
                        item.dedupKey);
                object.put(
                        "payload",
                        item.payload);
                object.put(
                        "createdAtMs",
                        item.createdAtMs);

                array.put(
                        object);
            } catch (JSONException ignored) {
            }
        }

        preferences.edit()
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
