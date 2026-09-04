package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

final class UserProfileRoomStore {
    private static final String PREFS =
            "prefs";

    private static final String KEY =
            "user_profiles_json";

    private static final String MIGRATION_KEY =
            "user_profiles_room_migration_complete_v1";

    private static final ExecutorService DB_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread =
                                new Thread(
                                        runnable,
                                        "ScaleLauncherUserProfiles");
                        thread.setDaemon(true);
                        return thread;
                    });

    private static volatile boolean migrationVerified;

    private interface DatabaseOperation<T> {
        T run(ScaleLauncherDatabase database);
    }

    private static final class LegacyData {
        final boolean valid;
        final List<UserProfileEntity> items;

        LegacyData(
                boolean valid,
                List<UserProfileEntity> items) {
            this.valid = valid;
            this.items = items;
        }
    }

    private UserProfileRoomStore() {
    }

    static List<UserProfile> load(
            Context context) {
        return runRoom(
                context,
                database ->
                        load(
                                database.userProfileDao()));
    }

    static void save(
            Context context,
            List<UserProfile> profiles) {
        runRoom(
                context,
                database -> {
                    database.runInTransaction(
                            () ->
                                    save(
                                            database.userProfileDao(),
                                            profiles));

                    return null;
                });
    }

    static List<UserProfile> synchronize(
            Context context,
            SharedPreferences preferences,
            List<OpenScaleProvider.User> users,
            String localDeviceId) {
        return runRoom(
                context,
                database -> {
                    AtomicReference<List<UserProfile>> result =
                            new AtomicReference<>();

                    database.runInTransaction(
                            () ->
                                    result.set(
                                            synchronize(
                                                    database.userProfileDao(),
                                                    preferences,
                                                    users,
                                                    localDeviceId)));

                    return result.get();
                });
    }

    static void updateReferenceWeight(
            Context context,
            long userId,
            float referenceWeightKg) {
        runRoom(
                context,
                database -> {
                    database.runInTransaction(
                            () ->
                                    updateReferenceWeight(
                                            database.userProfileDao(),
                                            userId,
                                            referenceWeightKg));

                    return null;
                });
    }

    static List<UserProfile> load(
            UserProfileDao dao) {
        if (dao == null) {
            throw new IllegalArgumentException(
                    "User profile DAO is required");
        }

        List<UserProfile> result =
                new ArrayList<>();

        for (UserProfileEntity entity :
                dao.loadAll()) {
            result.add(
                    fromEntity(
                            entity));
        }

        return result;
    }

    static void save(
            UserProfileDao dao,
            List<UserProfile> profiles) {
        if (dao == null
                || profiles == null) {
            throw new IllegalArgumentException(
                    "User profile DAO and profiles are required");
        }

        List<UserProfileEntity> entities =
                encodeProfiles(
                        profiles);

        dao.deleteAll();

        for (UserProfileEntity entity :
                entities) {
            if (dao.insert(
                    entity) == -1L) {
                throw new IllegalStateException(
                        "User profile insert failed");
            }
        }

        if (dao.count()
                != entities.size()) {
            throw new IllegalStateException(
                    "User profile save verification failed");
        }
    }

    static List<UserProfile> synchronize(
            UserProfileDao dao,
            SharedPreferences preferences,
            List<OpenScaleProvider.User> users,
            String localDeviceId) {
        if (dao == null
                || preferences == null
                || users == null) {
            throw new IllegalArgumentException(
                    "User profile synchronization arguments are required");
        }

        Map<Long, UserProfile> byId =
                new LinkedHashMap<>();

        for (UserProfile profile :
                load(
                        dao)) {
            byId.put(
                    profile.userId,
                    profile);
        }

        boolean migrateLegacy =
                byId.isEmpty();

        long oldUserId =
                preferences.getLong(
                        "openscale_user_id",
                        -1L);

        long now =
                System.currentTimeMillis();

        boolean validLocalDeviceId =
                PeerTrustStore.isValidDeviceId(
                        localDeviceId);

        for (OpenScaleProvider.User user :
                users) {
            UserProfile profile =
                    byId.get(
                            user.id);

            if (profile == null) {
                profile =
                        new UserProfile(
                                user.id,
                                user.name);

                profile.enabled =
                        true;

                if (migrateLegacy
                        && user.id
                                == oldUserId) {
                    profile.birthDateIso =
                            preferences.getString(
                                    "birth_date",
                                    "");

                    profile.heightCm =
                            preferences.getFloat(
                                    "height_cm",
                                    0f);

                    profile.male =
                            preferences.getInt(
                                    "sex",
                                    0) == 1;
                }

                byId.put(
                        user.id,
                        profile);
            }

            profile.name =
                    user.name;

            if (validLocalDeviceId) {
                boolean previousOwnerWasRemote =
                        PeerTrustStore.isValidDeviceId(
                                profile.ownerDeviceId)
                                && !localDeviceId.equals(
                                        profile.ownerDeviceId);

                if (!localDeviceId.equals(
                        profile.ownerDeviceId)) {
                    profile.ownerDeviceId =
                            localDeviceId;

                    if (previousOwnerWasRemote) {
                        profile.householdProfileId =
                                "";
                    }

                    profile.householdUpdatedAtMs =
                            now;
                }

                if (!UserProfile.isValidHouseholdProfileId(
                        profile.householdProfileId)) {
                    profile.ensureHouseholdProfileId();

                    profile.householdUpdatedAtMs =
                            now;
                } else if (profile.householdUpdatedAtMs
                        <= 0L) {
                    profile.householdUpdatedAtMs =
                            now;
                }
            }
        }

        List<UserProfile> synchronizedProfiles =
                new ArrayList<>();

        for (OpenScaleProvider.User user :
                users) {
            UserProfile profile =
                    byId.get(
                            user.id);

            if (profile != null) {
                synchronizedProfiles.add(
                        profile);
            }
        }

        save(
                dao,
                synchronizedProfiles);

        java.util.HashSet<Long> currentUserIds =
                new java.util.HashSet<>();

        for (OpenScaleProvider.User user :
                users) {
            currentUserIds.add(
                    user.id);
        }

        SharedPreferences.Editor cleanup =
                preferences.edit();

        boolean cleanupNeeded =
                false;

        long healthUserId =
                preferences.getLong(
                        "health_connect_user_id",
                        -1L);

        if (healthUserId >= 0L
                && !currentUserIds.contains(
                        healthUserId)) {
            cleanup.remove(
                    "health_connect_user_id");

            cleanupNeeded =
                    true;
        }

        long editorUserId =
                preferences.getLong(
                        "profile_editor_user_id",
                        -1L);

        if (editorUserId >= 0L
                && !currentUserIds.contains(
                        editorUserId)) {
            cleanup.remove(
                    "profile_editor_user_id");

            cleanupNeeded =
                    true;
        }

        long legacyUserId =
                preferences.getLong(
                        "openscale_user_id",
                        -1L);

        if (legacyUserId >= 0L
                && !currentUserIds.contains(
                        legacyUserId)) {
            cleanup.remove(
                    "openscale_user_id");

            cleanupNeeded =
                    true;
        }

        if (cleanupNeeded) {
            cleanup.apply();
        }

        if (migrateLegacy
                && oldUserId >= 0L
                && preferences.getBoolean(
                        "health_connect_enabled",
                        false)
                && preferences.getLong(
                        "health_connect_user_id",
                        -1L) < 0L) {
            preferences.edit()
                    .putLong(
                            "health_connect_user_id",
                            oldUserId)
                    .apply();
        }

        return synchronizedProfiles;
    }

    static UserProfile find(
            List<UserProfile> profiles,
            long userId) {
        if (profiles == null) {
            return null;
        }

        for (UserProfile profile :
                profiles) {
            if (profile.userId
                    == userId) {
                return profile;
            }
        }

        return null;
    }

    static UserProfile findByHouseholdProfileId(
            List<UserProfile> profiles,
            String householdProfileId) {
        if (profiles == null
                || !UserProfile.isValidHouseholdProfileId(
                        householdProfileId)) {
            return null;
        }

        for (UserProfile profile :
                profiles) {
            if (householdProfileId.equals(
                    profile.householdProfileId)) {
                return profile;
            }
        }

        return null;
    }

    static List<UserProfile> enabled(
            List<UserProfile> profiles) {
        if (profiles == null) {
            return new ArrayList<>();
        }

        List<UserProfile> enabled =
                new ArrayList<>();

        for (UserProfile profile :
                profiles) {
            if (profile.enabled) {
                enabled.add(
                        profile);
            }
        }

        return enabled;
    }

    static void updateReferenceWeight(
            UserProfileDao dao,
            long userId,
            float referenceWeightKg) {
        if (dao == null
                || !Float.isFinite(
                        referenceWeightKg)
                || referenceWeightKg <= 0f) {
            return;
        }

        List<UserProfile> profiles =
                load(
                        dao);

        UserProfile profile =
                find(
                        profiles,
                        userId);

        if (profile == null) {
            return;
        }

        profile.referenceWeightKg =
                referenceWeightKg;

        save(
                dao,
                profiles);
    }

    static boolean migrateLegacyForTest(
            SharedPreferences preferences,
            UserProfileDao dao) {
        LegacyData legacy =
                parseLegacy(
                        preferences);

        if (!legacy.valid
                || !importLegacy(
                        dao,
                        legacy)) {
            return false;
        }

        return preferences
                .edit()
                .putBoolean(
                        MIGRATION_KEY,
                        true)
                .commit();
    }

    static boolean isLegacyMigrationMarked(
            SharedPreferences preferences) {
        return preferences != null
                && preferences.getBoolean(
                        MIGRATION_KEY,
                        false);
    }

    private static <T> T runRoom(
            Context context,
            DatabaseOperation<T> operation) {
        if (context == null
                || operation == null) {
            throw new IllegalArgumentException(
                    "Context and operation are required");
        }

        Context appContext =
                context.getApplicationContext();

        try {
            return DB_EXECUTOR
                    .submit(
                            () -> {
                                ScaleLauncherDatabase database =
                                        ScaleLauncherDatabase.get(
                                                appContext);

                                if (!ensureLegacyMigrated(
                                        appContext,
                                        database)) {
                                    throw new IllegalStateException(
                                            "User profile migration failed");
                                }

                                return operation.run(
                                        database);
                            })
                    .get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "User profile Room operation interrupted",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause =
                    exception.getCause();

            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }

            throw new IllegalStateException(
                    "User profile Room operation failed",
                    cause);
        }
    }

    private static boolean ensureLegacyMigrated(
            Context context,
            ScaleLauncherDatabase database) {
        if (migrationVerified) {
            return true;
        }

        SharedPreferences preferences =
                context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE);

        if (preferences.getBoolean(
                MIGRATION_KEY,
                false)) {
            migrationVerified =
                    true;

            return true;
        }

        LegacyData legacy =
                parseLegacy(
                        preferences);

        if (!legacy.valid) {
            return false;
        }

        try {
            database.runInTransaction(
                    () -> {
                        if (!importLegacy(
                                database.userProfileDao(),
                                legacy)) {
                            throw new IllegalStateException(
                                    "User profile migration conflict");
                        }
                    });
        } catch (RuntimeException exception) {
            return false;
        }

        boolean markerStored =
                preferences
                        .edit()
                        .putBoolean(
                                MIGRATION_KEY,
                                true)
                        .commit();

        if (!markerStored) {
            return false;
        }

        migrationVerified =
                true;

        return true;
    }

    private static LegacyData parseLegacy(
            SharedPreferences preferences) {
        if (preferences == null) {
            return invalidLegacy();
        }

        String encoded =
                preferences.getString(
                        KEY,
                        "");

        if (encoded == null
                || encoded.isBlank()) {
            return new LegacyData(
                    true,
                    List.of());
        }

        try {
            JSONArray array =
                    new JSONArray(
                            encoded);

            Map<Long, UserProfileEntity> byId =
                    new LinkedHashMap<>();

            for (int index = 0;
                 index < array.length();
                 index++) {
                JSONObject object =
                        array.optJSONObject(
                                index);

                if (object == null) {
                    return invalidLegacy();
                }

                UserProfile profile =
                        UserProfile.fromJson(
                                object);

                if (!isPersistable(
                        profile)
                        || byId.containsKey(
                                profile.userId)) {
                    return invalidLegacy();
                }

                byId.put(
                        profile.userId,
                        toEntity(
                                profile,
                                index));
            }

            return new LegacyData(
                    true,
                    new ArrayList<>(
                            byId.values()));
        } catch (JSONException exception) {
            return invalidLegacy();
        }
    }

    private static boolean importLegacy(
            UserProfileDao dao,
            LegacyData legacy) {
        if (dao == null
                || legacy == null
                || !legacy.valid) {
            return false;
        }

        for (UserProfileEntity incoming :
                legacy.items) {
            UserProfileEntity existing =
                    dao.find(
                            incoming.userId);

            if (existing != null) {
                if (!sameStoredData(
                        existing,
                        incoming)) {
                    return false;
                }

                continue;
            }

            long inserted =
                    dao.insert(
                            incoming);

            if (inserted != -1L) {
                continue;
            }

            existing =
                    dao.find(
                            incoming.userId);

            if (existing == null
                    || !sameStoredData(
                            existing,
                            incoming)) {
                return false;
            }
        }

        return dao.count()
                == legacy.items.size();
    }

    private static List<UserProfileEntity> encodeProfiles(
            List<UserProfile> profiles) {
        Map<Long, UserProfileEntity> byId =
                new LinkedHashMap<>();

        for (int index = 0;
             index < profiles.size();
             index++) {
            UserProfile profile =
                    profiles.get(
                            index);

            if (!isPersistable(
                    profile)
                    || byId.containsKey(
                            profile.userId)) {
                throw new IllegalArgumentException(
                        "Invalid or duplicate user profile");
            }

            byId.put(
                    profile.userId,
                    toEntity(
                            profile,
                            index));
        }

        return new ArrayList<>(
                byId.values());
    }

    private static boolean isPersistable(
            UserProfile profile) {
        return profile != null
                && profile.userId >= 0L
                && Float.isFinite(
                        profile.heightCm)
                && Float.isFinite(
                        profile.referenceWeightKg)
                && Float.isFinite(
                        profile.toleranceKg);
    }

    private static LegacyData invalidLegacy() {
        return new LegacyData(
                false,
                List.of());
    }

    private static UserProfileEntity toEntity(
            UserProfile profile,
            long sortOrder) {
        return new UserProfileEntity(
                profile.userId,
                profile.name == null
                        ? ""
                        : profile.name,
                profile.enabled,
                profile.birthDateIso == null
                        ? ""
                        : profile.birthDateIso,
                profile.heightCm,
                profile.male,
                profile.referenceWeightKg,
                profile.toleranceKg,
                profile.ownerDeviceId == null
                        ? ""
                        : profile.ownerDeviceId,
                profile.householdProfileId == null
                        ? ""
                        : profile.householdProfileId,
                profile.householdUpdatedAtMs,
                sortOrder);
    }

    private static UserProfile fromEntity(
            UserProfileEntity entity) {
        if (entity == null
                || entity.userId < 0L
                || entity.sortOrder < 0L
                || !Float.isFinite(
                        entity.heightCm)
                || !Float.isFinite(
                        entity.referenceWeightKg)
                || !Float.isFinite(
                        entity.toleranceKg)) {
            throw new IllegalStateException(
                    "Invalid user profile Room row");
        }

        UserProfile profile =
                new UserProfile(
                        entity.userId,
                        entity.name);

        profile.enabled =
                entity.enabled;

        profile.birthDateIso =
                entity.birthDateIso;

        profile.heightCm =
                entity.heightCm;

        profile.male =
                entity.male;

        profile.referenceWeightKg =
                entity.referenceWeightKg;

        profile.toleranceKg =
                entity.toleranceKg;

        profile.ownerDeviceId =
                entity.ownerDeviceId;

        profile.householdProfileId =
                entity.householdProfileId;

        profile.householdUpdatedAtMs =
                entity.householdUpdatedAtMs;

        return profile;
    }

    private static boolean sameStoredData(
            UserProfileEntity first,
            UserProfileEntity second) {
        return first.userId
                        == second.userId
                && first.name.equals(
                        second.name)
                && first.enabled
                        == second.enabled
                && first.birthDateIso.equals(
                        second.birthDateIso)
                && Float.compare(
                        first.heightCm,
                        second.heightCm) == 0
                && first.male
                        == second.male
                && Float.compare(
                        first.referenceWeightKg,
                        second.referenceWeightKg) == 0
                && Float.compare(
                        first.toleranceKg,
                        second.toleranceKg) == 0
                && first.ownerDeviceId.equals(
                        second.ownerDeviceId)
                && first.householdProfileId.equals(
                        second.householdProfileId)
                && first.householdUpdatedAtMs
                        == second.householdUpdatedAtMs
                && first.sortOrder
                        == second.sortOrder;
    }
}
