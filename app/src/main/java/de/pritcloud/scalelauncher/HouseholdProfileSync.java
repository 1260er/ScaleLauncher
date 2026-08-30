package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

final class HouseholdProfileSync {
    private HouseholdProfileSync() {}

    static boolean publishProfile(
            Context context,
            SharedPreferences prefs,
            long userId) {
        List<UserProfile> profiles =
                UserProfileStore.load(prefs);

        UserProfile profile =
                UserProfileStore.find(
                        profiles,
                        userId);

        if (profile == null
                || !profile.hasValidMatchingData()) {
            return false;
        }

        boolean changed =
                prepareIdentity(
                        context,
                        profile,
                        true);

        if (changed) {
            UserProfileStore.save(
                    prefs,
                    profiles);
        }

        if (!UserProfile.isValidHouseholdProfileId(
                profile.householdProfileId)) {
            return false;
        }

        return publishPrepared(
                context,
                profile,
                null);
    }

    static boolean updateReferenceWeight(
            Context context,
            SharedPreferences prefs,
            long userId,
            float referenceWeightKg) {
        if (!Float.isFinite(referenceWeightKg)
                || referenceWeightKg <= 0f) {
            return false;
        }

        List<UserProfile> profiles =
                UserProfileStore.load(prefs);

        UserProfile profile =
                UserProfileStore.find(
                        profiles,
                        userId);

        if (profile == null) {
            return false;
        }

        profile.referenceWeightKg =
                referenceWeightKg;

        prepareIdentity(
                context,
                profile,
                true);

        UserProfileStore.save(
                prefs,
                profiles);

        return publishPrepared(
                context,
                profile,
                null);
    }

    static int enqueueAllProfilesForPeer(
            Context context,
            SharedPreferences prefs,
            String peerDeviceId) {
        PeerTrustStore.Peer peer =
                PeerTrustStore.find(
                        context,
                        peerDeviceId);

        if (peer == null) {
            return 0;
        }

        List<UserProfile> profiles =
                UserProfileStore.load(prefs);

        boolean changed = false;
        int queued = 0;

        for (UserProfile profile :
                profiles) {
            if (!profile.hasValidMatchingData()) {
                continue;
            }

            changed |=
                    prepareIdentity(
                            context,
                            profile,
                            false);
        }

        if (changed) {
            UserProfileStore.save(
                    prefs,
                    profiles);
        }

        List<String> ownerProfileIds =
                currentOwnedProfileIds(
                        context,
                        profiles);

        for (UserProfile profile :
                profiles) {
            if (!profile.hasValidMatchingData()
                    || !ownerProfileIds.contains(
                            profile.householdProfileId)) {
                continue;
            }

            try {
                HouseholdProfile household =
                        HouseholdProfile.fromUserProfile(
                                profile,
                                PeerTrustStore.localDeviceId(
                                        context),
                                profile.householdUpdatedAtMs);

                HouseholdProfileStore.upsert(
                        context,
                        household);

                PeerOutboxStore.enqueueProfile(
                        context,
                        peer.deviceId,
                        PeerProfilePayload.fromProfile(
                                household,
                                ownerProfileIds));

                queued++;
            } catch (RuntimeException exception) {
                EventLog.warning(
                        context,
                        context.getString(
                                R.string.log_peer_profile_queue_failed,
                                peer.label));
            }
        }

        try {
            PeerOutboxStore.enqueueProfileManifest(
                    context,
                    peer.deviceId,
                    PeerProfileManifestPayload.create(
                            ownerProfileIds));

            queued++;
        } catch (RuntimeException exception) {
            EventLog.warning(
                    context,
                    context.getString(
                            R.string.log_peer_profile_queue_failed,
                            peer.label));
        }

        return queued;
    }

    static boolean acceptIncomingProfile(
            Context context,
            SharedPreferences prefs,
            PeerTrustStore.Peer peer,
            HouseholdProfile incoming,
            List<String> ownerProfileIds) {
        if (peer == null
                || incoming == null
                || !incoming.isValid()
                || !incoming.ownerDeviceId.equals(
                        peer.deviceId)) {
            return false;
        }

        boolean senderIsOwner =
                incoming.ownerDeviceId.equals(
                        peer.deviceId);

        List<UserProfile> localProfiles =
                UserProfileStore.load(prefs);

        boolean localChanged =
                false;

        String localDeviceId =
                PeerTrustStore.localDeviceId(
                        context);

        for (UserProfile local :
                localProfiles) {
            /*
             * householdProfileId is the ONLY identity.
             *
             * Names are display data and may occur more than once.
             * Never link, merge or replace profiles by name.
             */
            if (!incoming.profileId.equals(
                    local.householdProfileId)) {
                continue;
            }

            /*
             * A profile that exists in local openScale belongs to this
             * device. A remote peer must never take over its stable
             * household identity, even with a newer revision.
             */
            if (hasOwnerConflict(
                    localDeviceId,
                    incoming)) {
                return false;
            }

            if (incoming.updatedAtMs
                    > local.householdUpdatedAtMs) {
                local.referenceWeightKg =
                        incoming.referenceWeightKg;

                local.toleranceKg =
                        incoming.toleranceKg;

                local.ownerDeviceId =
                        incoming.ownerDeviceId;

                local.householdUpdatedAtMs =
                        incoming.updatedAtMs;

                localChanged =
                        true;
            }
        }

        if (localChanged) {
            UserProfileStore.save(
                    prefs,
                    localProfiles);
        }

        HouseholdProfileStore.upsert(
                context,
                incoming);

        if (senderIsOwner
                && ownerProfileIds != null
                && !ownerProfileIds.isEmpty()
                && ownerProfileIds.contains(
                        incoming.profileId)) {
            int removed =
                    HouseholdProfileStore.removeOwnerExcept(
                            context,
                            peer.deviceId,
                            ownerProfileIds);

            if (removed > 0) {
                EventLog.info(
                        context,
                        context.getString(
                                R.string.log_peer_profiles_pruned,
                                removed,
                                peer.label));
            }
        }

        return true;
    }

    static boolean hasOwnerConflict(
            String localDeviceId,
            HouseholdProfile incoming) {
        return incoming != null
                && !localDeviceId.equals(
                        incoming.ownerDeviceId);
    }

    static boolean acceptIncomingManifest(
            Context context,
            PeerTrustStore.Peer peer,
            List<String> ownerProfileIds) {
        if (context == null
                || peer == null
                || ownerProfileIds == null
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

        int removed =
                HouseholdProfileStore.removeOwnerExcept(
                        context,
                        peer.deviceId,
                        ownerProfileIds);

        if (removed > 0) {
            EventLog.info(
                    context,
                    context.getString(
                            R.string.log_peer_profiles_pruned,
                            removed,
                            peer.label));
        }

        return true;
    }

    private static boolean publishPrepared(
            Context context,
            UserProfile profile,
            PeerTrustStore.Peer onlyPeer) {
        try {
            HouseholdProfile household =
                    HouseholdProfile.fromUserProfile(
                            profile,
                            PeerTrustStore.localDeviceId(
                                    context),
                            profile.householdUpdatedAtMs);

            HouseholdProfileStore.upsert(
                    context,
                    household);

            int queued = 0;

            List<UserProfile> currentProfiles =
                    UserProfileStore.load(
                            context.getSharedPreferences(
                                    "prefs",
                                    Context.MODE_PRIVATE));

            List<String> ownerProfileIds =
                    currentOwnedProfileIds(
                            context,
                            currentProfiles);

            if (onlyPeer != null) {
                queued +=
                        enqueue(
                                context,
                                onlyPeer,
                                household,
                                ownerProfileIds);
            } else {
                for (PeerTrustStore.Peer peer :
                        PeerTrustStore.load(context)) {
                    queued +=
                            enqueue(
                                    context,
                                    peer,
                                    household,
                                    ownerProfileIds);
                }
            }

            if (queued > 0) {
                EventLog.debug(
                        context,
                        context.getString(
                                R.string.log_peer_profile_queued,
                                profile.name,
                                queued));
            }

            return true;
        } catch (RuntimeException exception) {
            EventLog.warning(
                    context,
                    context.getString(
                            R.string.log_peer_profile_sync_failed,
                            profile.name));

            return false;
        }
    }

    private static int enqueue(
            Context context,
            PeerTrustStore.Peer peer,
            HouseholdProfile profile,
            List<String> ownerProfileIds) {
        try {
            PeerOutboxStore.enqueueProfile(
                    context,
                    peer.deviceId,
                    PeerProfilePayload.fromProfile(
                            profile,
                            ownerProfileIds));

            return 1;
        } catch (RuntimeException exception) {
            EventLog.warning(
                    context,
                    context.getString(
                            R.string.log_peer_profile_queue_failed,
                            peer.label));

            return 0;
        }
    }

    static int pruneStaleLocalProfiles(
            Context context,
            SharedPreferences prefs) {
        if (context == null
                || prefs == null) {
            return 0;
        }

        List<UserProfile> profiles =
                UserProfileStore.load(prefs);

        List<String> ownerProfileIds =
                currentOwnedProfileIds(
                        context,
                        profiles);

        return HouseholdProfileStore.removeOwnerExcept(
                context,
                PeerTrustStore.localDeviceId(
                        context),
                ownerProfileIds);
    }

    private static List<String> currentOwnedProfileIds(
            Context context,
            List<UserProfile> profiles) {
        List<String> result =
                new java.util.ArrayList<>();

        String localDeviceId =
                PeerTrustStore.localDeviceId(
                        context);

        if (profiles == null) {
            return result;
        }

        for (UserProfile profile :
                profiles) {
            if (profile == null
                    || !profile.hasValidMatchingData()
                    || !localDeviceId.equals(
                            profile.ownerDeviceId)
                    || !UserProfile.isValidHouseholdProfileId(
                            profile.householdProfileId)) {
                continue;
            }

            if (!result.contains(
                    profile.householdProfileId)) {
                result.add(
                        profile.householdProfileId);
            }
        }

        return result;
    }

    private static boolean prepareIdentity(
            Context context,
            UserProfile profile,
            boolean bumpRevision) {
        boolean changed = false;

        String localDeviceId =
                PeerTrustStore.localDeviceId(
                        context);

        if (!PeerTrustStore.isValidDeviceId(
                profile.ownerDeviceId)) {
            profile.ownerDeviceId =
                    localDeviceId;

            changed = true;
        }

        /*
         * Only the owning phone may mint a new household profile ID.
         * A phone that merely knows about a remote user must wait for
         * the canonical ID sent by that users owning phone.
         */
        if (!UserProfile.isValidHouseholdProfileId(
                        profile.householdProfileId)
                && localDeviceId.equals(
                        profile.ownerDeviceId)) {
            profile.ensureHouseholdProfileId();
            changed = true;
        }

        if (UserProfile.isValidHouseholdProfileId(
                        profile.householdProfileId)
                && localDeviceId.equals(
                        profile.ownerDeviceId)
                && (bumpRevision
                    || profile.householdUpdatedAtMs <= 0L)) {
            profile.householdUpdatedAtMs =
                    profile.householdUpdatedAtMs <= 0L
                            ? 1L
                            : profile.householdUpdatedAtMs + 1L;

            changed = true;
        }

        return changed;
    }
}
