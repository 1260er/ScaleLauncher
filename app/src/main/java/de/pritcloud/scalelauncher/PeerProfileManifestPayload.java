package de.pritcloud.scalelauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PeerProfileManifestPayload {
    static final int VERSION = 1;

    static final String TYPE =
            "household_profile_manifest";

    final String messageId;
    final List<String> ownerProfileIds;

    private PeerProfileManifestPayload(
            String messageId,
            List<String> ownerProfileIds) {
        this.messageId =
                messageId == null
                        ? ""
                        : messageId;

        this.ownerProfileIds =
                ownerProfileIds == null
                        ? List.of()
                        : List.copyOf(ownerProfileIds);
    }

    static PeerProfileManifestPayload create(
            List<String> ownerProfileIds) {
        PeerProfileManifestPayload payload =
                new PeerProfileManifestPayload(
                        UUID.randomUUID().toString(),
                        ownerProfileIds);

        if (!payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid peer profile manifest");
        }

        return payload;
    }

    boolean isValid() {
        if (!UserProfile.isValidHouseholdProfileId(
                        messageId)
                || ownerProfileIds.size() > 100) {
            return false;
        }

        for (String profileId :
                ownerProfileIds) {
            if (!UserProfile.isValidHouseholdProfileId(
                    profileId)) {
                return false;
            }
        }

        return true;
    }

    String encode() {
        if (!isValid()) {
            throw new IllegalStateException(
                    "Invalid peer profile manifest");
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

            JSONArray ids =
                    new JSONArray();

            for (String profileId :
                    ownerProfileIds) {
                ids.put(
                        profileId);
            }

            object.put(
                    "ownerProfileIds",
                    ids);

            return object.toString();
        } catch (JSONException exception) {
            throw new IllegalStateException(
                    "Could not encode peer profile manifest",
                    exception);
        }
    }

    static PeerProfileManifestPayload decode(
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

            JSONArray ids =
                    object.optJSONArray(
                            "ownerProfileIds");

            if (ids == null) {
                return null;
            }

            List<String> ownerProfileIds =
                    new ArrayList<>();

            for (int index = 0;
                 index < ids.length();
                 index++) {
                ownerProfileIds.add(
                        ids.optString(
                                index,
                                ""));
            }

            PeerProfileManifestPayload payload =
                    new PeerProfileManifestPayload(
                            object.optString(
                                    "messageId",
                                    ""),
                            ownerProfileIds);

            return payload.isValid()
                    ? payload
                    : null;
        } catch (JSONException exception) {
            return null;
        }
    }
}
