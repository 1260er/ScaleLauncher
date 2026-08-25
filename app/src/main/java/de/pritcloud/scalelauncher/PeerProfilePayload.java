package de.pritcloud.scalelauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PeerProfilePayload {
    static final int VERSION = 1;
    static final String TYPE =
            "household_profile_upsert";

    final String messageId;
    final HouseholdProfile profile;
    final List<String> ownerProfileIds;

    private PeerProfilePayload(
            String messageId,
            HouseholdProfile profile,
            List<String> ownerProfileIds) {
        this.messageId = messageId;
        this.profile = profile;
        this.ownerProfileIds =
                ownerProfileIds == null
                        ? List.of()
                        : List.copyOf(ownerProfileIds);
    }

    static PeerProfilePayload fromProfile(
            HouseholdProfile profile) {
        return fromProfile(
                profile,
                List.of());
    }

    static PeerProfilePayload fromProfile(
            HouseholdProfile profile,
            List<String> ownerProfileIds) {
        PeerProfilePayload payload =
                new PeerProfilePayload(
                        UUID.randomUUID().toString(),
                        profile,
                        ownerProfileIds);

        if (!payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid peer profile payload");
        }

        return payload;
    }

    boolean isValid() {
        if (!UserProfile.isValidHouseholdProfileId(
                        messageId)
                || profile == null
                || !profile.isValid()
                || ownerProfileIds.size() > 100) {
            return false;
        }

        if (ownerProfileIds.isEmpty()) {
            return true;
        }

        boolean containsProfile =
                false;

        for (String profileId :
                ownerProfileIds) {
            if (!UserProfile.isValidHouseholdProfileId(
                    profileId)) {
                return false;
            }

            if (profile.profileId.equals(
                    profileId)) {
                containsProfile =
                        true;
            }
        }

        return containsProfile;
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

            if (!ownerProfileIds.isEmpty()) {
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
            }

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

            List<String> ownerProfileIds =
                    new ArrayList<>();

            JSONArray ids =
                    object.optJSONArray(
                            "ownerProfileIds");

            if (ids != null) {
                for (int index = 0;
                     index < ids.length();
                     index++) {
                    ownerProfileIds.add(
                            ids.optString(
                                    index,
                                    ""));
                }
            }

            PeerProfilePayload payload =
                    new PeerProfilePayload(
                            object.optString(
                                    "messageId",
                                    ""),
                            HouseholdProfile.fromJson(
                                    object.optJSONObject(
                                            "profile")),
                            ownerProfileIds);

            return payload.isValid()
                    ? payload
                    : null;
        } catch (JSONException exception) {
            return null;
        }
    }
}
