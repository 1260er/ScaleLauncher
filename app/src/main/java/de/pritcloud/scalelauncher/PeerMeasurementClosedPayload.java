package de.pritcloud.scalelauncher;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

final class PeerMeasurementClosedPayload {
    static final int VERSION = 1;

    static final String TYPE =
            "measurement_closed";

    final String messageId;
    final String measurementId;

    private PeerMeasurementClosedPayload(
            String messageId,
            String measurementId) {
        this.messageId =
                messageId == null
                        ? ""
                        : messageId;
        this.measurementId =
                measurementId == null
                        ? ""
                        : measurementId;
    }

    static PeerMeasurementClosedPayload create(
            String measurementId) {
        PeerMeasurementClosedPayload payload =
                new PeerMeasurementClosedPayload(
                        UUID.randomUUID().toString(),
                        measurementId);

        if (!payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid closed measurement payload");
        }

        return payload;
    }

    boolean isValid() {
        return UserProfile.isValidHouseholdProfileId(
                        messageId)
                && !measurementId.isBlank()
                && measurementId.length() <= 200;
    }

    String encode() {
        if (!isValid()) {
            throw new IllegalStateException(
                    "Invalid closed measurement payload");
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
                    "measurementId",
                    measurementId);

            return object.toString();
        } catch (JSONException exception) {
            throw new IllegalStateException(
                    "Could not encode closed measurement payload",
                    exception);
        }
    }

    static PeerMeasurementClosedPayload decode(
            String encoded) {
        if (encoded == null
                || encoded.isBlank()) {
            return null;
        }

        try {
            JSONObject object =
                    new JSONObject(
                            encoded);

            if (object.optInt(
                    "version",
                    -1) != VERSION
                    || !TYPE.equals(
                            object.optString(
                                    "type",
                                    ""))) {
                return null;
            }

            PeerMeasurementClosedPayload payload =
                    new PeerMeasurementClosedPayload(
                            object.optString(
                                    "messageId",
                                    ""),
                            object.optString(
                                    "measurementId",
                                    ""));

            return payload.isValid()
                    ? payload
                    : null;
        } catch (JSONException exception) {
            return null;
        }
    }
}
