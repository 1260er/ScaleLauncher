package de.pritcloud.scalelauncher;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

final class PeerProfilePayload {
    static final int VERSION = 1;
    static final String TYPE =
            "household_profile_upsert";

    final String messageId;
    final HouseholdProfile profile;

    private PeerProfilePayload(
            String messageId,
            HouseholdProfile profile) {
        this.messageId = messageId;
        this.profile = profile;
    }

    static PeerProfilePayload fromProfile(
            HouseholdProfile profile) {
        PeerProfilePayload payload =
                new PeerProfilePayload(
                        UUID.randomUUID().toString(),
                        profile);

        if (!payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid peer profile payload");
        }

        return payload;
    }

    boolean isValid() {
        return UserProfile.isValidHouseholdProfileId(
                        messageId)
                && profile != null
                && profile.isValid();
    }

    String encode() {
        if (!isValid()) {
            throw new IllegalStateException(
                    "Invalid peer profile payload");
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
                    "profile",
                    profile.toJson());

            return object.toString();
        } catch (JSONException exception) {
            throw new IllegalStateException(
                    "Could not encode peer profile",
                    exception);
        }
    }

    static PeerProfilePayload decode(
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

            PeerProfilePayload payload =
                    new PeerProfilePayload(
                            object.optString(
                                    "messageId",
                                    ""),
                            HouseholdProfile.fromJson(
                                    object.optJSONObject(
                                            "profile")));

            return payload.isValid()
                    ? payload
                    : null;
        } catch (JSONException exception) {
            return null;
        }
    }
}
