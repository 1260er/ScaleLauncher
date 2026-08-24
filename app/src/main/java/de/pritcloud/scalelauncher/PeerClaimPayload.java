package de.pritcloud.scalelauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Response to a measurement claim request.
 *
 * The peer identity is authenticated by the encrypted transport, therefore
 * the payload only contains the measurement ID and the local household
 * profile IDs that the receiving phone can legitimately claim.
 */
final class PeerClaimPayload {
    static final int VERSION = 1;
    static final String TYPE =
            "measurement_claim";

    final String messageId;
    final String measurementId;
    final List<String> claimedProfileIds;

    private PeerClaimPayload(
            String messageId,
            String measurementId,
            List<String> claimedProfileIds) {
        this.messageId = messageId;
        this.measurementId =
                measurementId == null
                        ? ""
                        : measurementId;
        this.claimedProfileIds =
                claimedProfileIds == null
                        ? List.of()
                        : List.copyOf(
                                claimedProfileIds);
    }

    static PeerClaimPayload create(
            String measurementId,
            List<String> claimedProfileIds) {
        PeerClaimPayload payload =
                new PeerClaimPayload(
                        UUID.randomUUID().toString(),
                        measurementId,
                        claimedProfileIds);

        if (!payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid peer claim payload");
        }

        return payload;
    }

    boolean isValid() {
        return UserProfile.isValidHouseholdProfileId(
                        messageId)
                && measurementId != null
                && !measurementId.isBlank()
                && measurementId.length() <= 200
                && validProfileIds(
                        claimedProfileIds);
    }

    String encode() {
        if (!isValid()) {
            throw new IllegalStateException(
                    "Invalid peer claim payload");
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

            JSONArray profileIds =
                    new JSONArray();

            for (String profileId :
                    claimedProfileIds) {
                profileIds.put(
                        profileId);
            }

            object.put(
                    "claimedProfileIds",
                    profileIds);

            return object.toString();
        } catch (JSONException exception) {
            throw new IllegalStateException(
                    "Could not encode peer claim",
                    exception);
        }
    }

    static PeerClaimPayload decode(
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

            List<String> profileIds =
                    new ArrayList<>();

            JSONArray array =
                    object.optJSONArray(
                            "claimedProfileIds");

            if (array != null) {
                for (int index = 0;
                     index < array.length();
                     index++) {
                    String profileId =
                            array.optString(
                                    index,
                                    "");

                    if (!profileId.isBlank()) {
                        profileIds.add(
                                profileId);
                    }
                }
            }

            PeerClaimPayload payload =
                    new PeerClaimPayload(
                            object.optString(
                                    "messageId",
                                    ""),
                            object.optString(
                                    "measurementId",
                                    ""),
                            profileIds);

            return payload.isValid()
                    ? payload
                    : null;
        } catch (JSONException exception) {
            return null;
        }
    }

    private static boolean validProfileIds(
            List<String> profileIds) {
        if (profileIds == null
                || profileIds.size() > 100) {
            return false;
        }

        Set<String> unique =
                new HashSet<>();

        for (String profileId :
                profileIds) {
            if (!UserProfile.isValidHouseholdProfileId(
                    profileId)
                    || !unique.add(
                            profileId)) {
                return false;
            }
        }

        return true;
    }
}
