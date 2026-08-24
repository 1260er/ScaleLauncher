package de.pritcloud.scalelauncher;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Explicit user decision for one ambiguous household measurement candidate.
 *
 * The sender identity comes from the authenticated peer transport. The
 * profile ID is a stable household ID; names are never used as identity.
 */
final class PeerMeasurementDecisionPayload {
    static final int VERSION = 1;
    static final String TYPE =
            "measurement_decision";

    static final String DECISION_ACCEPT =
            "accept";
    static final String DECISION_REJECT =
            "reject";

    final String messageId;
    final String measurementId;
    final String profileId;
    final String decision;

    private PeerMeasurementDecisionPayload(
            String messageId,
            String measurementId,
            String profileId,
            String decision) {
        this.messageId =
                messageId == null ? "" : messageId;
        this.measurementId =
                measurementId == null ? "" : measurementId;
        this.profileId =
                profileId == null ? "" : profileId;
        this.decision =
                decision == null ? "" : decision;
    }

    static PeerMeasurementDecisionPayload create(
            String measurementId,
            String profileId,
            boolean accepted) {
        PeerMeasurementDecisionPayload payload =
                new PeerMeasurementDecisionPayload(
                        UUID.randomUUID().toString(),
                        measurementId,
                        profileId,
                        accepted
                                ? DECISION_ACCEPT
                                : DECISION_REJECT);

        if (!payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid measurement decision payload");
        }

        return payload;
    }

    boolean isAccepted() {
        return DECISION_ACCEPT.equals(
                decision);
    }

    boolean isValid() {
        return UserProfile.isValidHouseholdProfileId(
                        messageId)
                && measurementId != null
                && !measurementId.isBlank()
                && measurementId.length() <= 200
                && UserProfile.isValidHouseholdProfileId(
                        profileId)
                && (DECISION_ACCEPT.equals(
                        decision)
                    || DECISION_REJECT.equals(
                        decision));
    }

    String encode() {
        if (!isValid()) {
            throw new IllegalStateException(
                    "Invalid measurement decision payload");
        }

        try {
            JSONObject object =
                    new JSONObject();

            object.put("version", VERSION);
            object.put("type", TYPE);
            object.put("messageId", messageId);
            object.put("measurementId", measurementId);
            object.put("profileId", profileId);
            object.put("decision", decision);

            return object.toString();
        } catch (JSONException exception) {
            throw new IllegalStateException(
                    "Could not encode measurement decision",
                    exception);
        }
    }

    static PeerMeasurementDecisionPayload decode(
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

            PeerMeasurementDecisionPayload payload =
                    new PeerMeasurementDecisionPayload(
                            object.optString(
                                    "messageId",
                                    ""),
                            object.optString(
                                    "measurementId",
                                    ""),
                            object.optString(
                                    "profileId",
                                    ""),
                            object.optString(
                                    "decision",
                                    ""));

            return payload.isValid()
                    ? payload
                    : null;
        } catch (JSONException exception) {
            return null;
        }
    }
}
