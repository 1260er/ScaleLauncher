package de.pritcloud.scalelauncher;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** Peer status message announcing whether the sender currently owns the collector role. */
final class PeerCollectorStatusPayload {
    static final int VERSION = 1;
    static final String TYPE =
            "collector_status";

    final String messageId;
    final boolean collector;

    private PeerCollectorStatusPayload(
            String messageId,
            boolean collector) {
        this.messageId =
                messageId;
        this.collector =
                collector;
    }

    static PeerCollectorStatusPayload create(
            boolean collector) {
        PeerCollectorStatusPayload payload =
                new PeerCollectorStatusPayload(
                        UUID.randomUUID().toString(),
                        collector);

        if (!payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid collector status payload");
        }

        return payload;
    }

    boolean isValid() {
        return UserProfile.isValidHouseholdProfileId(
                messageId);
    }

    String encode() {
        if (!isValid()) {
            throw new IllegalStateException(
                    "Invalid collector status payload");
        }

        try {
            JSONObject object =
                    new JSONObject();

            object.put(
                    "version",
                    VERSION);
            object.put(
                    "type",
                    TYPE);
            object.put(
                    "messageId",
                    messageId);
            object.put(
                    "collector",
                    collector);

            return object.toString();
        } catch (JSONException exception) {
            throw new IllegalStateException(
                    "Could not encode collector status",
                    exception);
        }
    }

    static PeerCollectorStatusPayload decode(
            String encoded) {
        if (encoded == null
                || encoded.isBlank()) {
            return null;
        }

        try {
            JSONObject object =
                    new JSONObject(encoded);

            if (object.optInt(
                    "version",
                    -1) != VERSION
                    || !TYPE.equals(
                            object.optString(
                                    "type",
                                    ""))) {
                return null;
            }

            if (!object.has("collector")) {
                return null;
            }

            PeerCollectorStatusPayload payload =
                    new PeerCollectorStatusPayload(
                            object.optString(
                                    "messageId",
                                    ""),
                            object.getBoolean(
                                    "collector"));

            return payload.isValid()
                    ? payload
                    : null;
        } catch (JSONException exception) {
            return null;
        }
    }
}
