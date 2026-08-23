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

        long now =
                System.currentTimeMillis();

        boolean changed =
                prepareIdentity(
                        context,
                        profile,
                        now,
                        true);

        if (changed) {
            UserProfileStore.save(
                    prefs,
                    profiles);
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
                System.currentTimeMillis(),
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

        long now =
                System.currentTimeMillis();

        for (UserProfile profile :
                profiles) {
            if (!profile.hasValidMatchingData()) {
                continue;
            }

            changed |=
                    prepareIdentity(
                            context,
                            profile,
                            now,
                            false);

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
                                household));

                queued++;
            } catch (RuntimeException exception) {
                EventLog.warning(
                        context,
                        context.getString(
                                R.string.log_peer_profile_queue_failed,
                                peer.label));
            }
        }

        if (changed) {
            UserProfileStore.save(
                    prefs,
                    profiles);
        }

        return queued;
    }

    static boolean acceptIncomingProfile(
            Context context,
            SharedPreferences prefs,
            PeerTrustStore.Peer peer,
            HouseholdProfile incoming) {
        if (peer == null
                || incoming == null
                || !incoming.isValid()) {
            return false;
        }

        boolean senderIsOwner =
                incoming.ownerDeviceId.equals(
                        peer.deviceId);

        List<UserProfile> localProfiles =
                UserProfileStore.load(prefs);

        boolean localChanged =
                false;

        for (UserProfile local :
                localProfiles) {
            boolean sameCanonicalId =
                    incoming.profileId.equals(
                            local.householdProfileId);

            boolean sameOwnerAndName =
                    senderIsOwner
                            && incoming.ownerDeviceId.equals(
                                    local.ownerDeviceId)
                            && incoming.name.trim()
                                    .equalsIgnoreCase(
                                            local.name.trim());

            if (!sameCanonicalId
                    && !sameOwnerAndName) {
                continue;
            }

            if (sameOwnerAndName
                    && !sameCanonicalId) {
                if (UserProfile.isValidHouseholdProfileId(
                        local.householdProfileId)) {
                    HouseholdProfileStore.removeProfile(
                            context,
                            local.householdProfileId);
                }

                local.householdProfileId =
                        incoming.profileId;

                localChanged =
                        true;
            }

            if (senderIsOwner
                    || incoming.updatedAtMs
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

        if (senderIsOwner) {
            HouseholdProfileStore.removeMatchingIdentityExcept(
                    context,
                    incoming.name,
                    incoming.ownerDeviceId,
                    incoming.profileId);
        }

        if (localChanged) {
            UserProfileStore.save(
                    prefs,
                    localProfiles);
        }

        HouseholdProfileStore.upsert(
                context,
                incoming);

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

            if (onlyPeer != null) {
                queued +=
                        enqueue(
                                context,
                                onlyPeer,
                                household);
            } else {
                for (PeerTrustStore.Peer peer :
                        PeerTrustStore.load(context)) {
                    queued +=
                            enqueue(
                                    context,
                                    peer,
                                    household);
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
            HouseholdProfile profile) {
        try {
            PeerOutboxStore.enqueueProfile(
                    context,
                    peer.deviceId,
                    PeerProfilePayload.fromProfile(
                            profile));

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

    private static boolean prepareIdentity(
            Context context,
            UserProfile profile,
            long now,
            boolean bumpTimestamp) {
        boolean changed = false;

        if (!UserProfile.isValidHouseholdProfileId(
                profile.householdProfileId)) {
            profile.ensureHouseholdProfileId();
            changed = true;
        }

        if (!PeerTrustStore.isValidDeviceId(
                profile.ownerDeviceId)) {
            profile.ownerDeviceId =
                    PeerTrustStore.localDeviceId(
                            context);

            changed = true;
        }

        if (bumpTimestamp
                || profile.householdUpdatedAtMs <= 0L) {
            profile.householdUpdatedAtMs =
                    Math.max(
                            1L,
                            now);

            changed = true;
        }

        return changed;
    }
}
