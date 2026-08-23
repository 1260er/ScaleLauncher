package de.pritcloud.scalelauncher;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

final class PeerAckPayload {
    static final int VERSION = 1;
    static final String TYPE =
            "peer_ack";

    final String messageId;
    final String acknowledgedMessageId;

    private PeerAckPayload(
            String messageId,
            String acknowledgedMessageId) {
        this.messageId = messageId;
        this.acknowledgedMessageId =
                acknowledgedMessageId;
    }

    static PeerAckPayload create(
            String acknowledgedMessageId) {
        PeerAckPayload payload =
                new PeerAckPayload(
                        UUID.randomUUID().toString(),
                        acknowledgedMessageId);

        if (!payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid peer ACK");
        }

        return payload;
    }

    boolean isValid() {
        return UserProfile.isValidHouseholdProfileId(
                        messageId)
                && acknowledgedMessageId != null
                && !acknowledgedMessageId.isBlank()
                && acknowledgedMessageId.length() <= 200;
    }

    String encode() {
        if (!isValid()) {
            throw new IllegalStateException(
                    "Invalid peer ACK");
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
                    "acknowledgedMessageId",
                    acknowledgedMessageId);

            return object.toString();
        } catch (JSONException exception) {
            throw new IllegalStateException(
                    "Could not encode peer ACK",
                    exception);
        }
    }

    static PeerAckPayload decode(
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

            PeerAckPayload payload =
                    new PeerAckPayload(
                            object.optString(
                                    "messageId",
                                    ""),
                            object.optString(
                                    "acknowledgedMessageId",
                                    ""));

            return payload.isValid()
                    ? payload
                    : null;
        } catch (JSONException exception) {
            return null;
        }
    }
}
