package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HouseholdProfileRoomStore {
    private static final String PREFS =
            "household_profiles_v1";

    private static final String KEY =
            "profiles";

    private static final String MIGRATION_KEY =
            "household_profiles_room_migration_complete_v1";

    private static final ExecutorService DB_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread =
                                new Thread(
                                        runnable,
                                        "ScaleLauncherHouseholdProfiles");
                        thread.setDaemon(true);
                        return thread;
                    });

    private static volatile boolean migrationVerified;

    private interface DatabaseOperation<T> {
        T run(ScaleLauncherDatabase database);
    }

    private static final class LegacyData {
        final boolean valid;
        final List<HouseholdProfileEntity> items;

        LegacyData(
                boolean valid,
                List<HouseholdProfileEntity> items) {
            this.valid = valid;
            this.items = items;
        }
    }

    private HouseholdProfileRoomStore() {
    }

    static List<HouseholdProfile> load(
            Context context) {
        return runRoom(
                context,
                database ->
                        load(
                                database.householdProfileDao()));
    }

    static HouseholdProfile find(
            Context context,
            String profileId) {
        return runRoom(
                context,
                database ->
                        find(
                                database.householdProfileDao(),
                                profileId));
    }

    static boolean upsert(
            Context context,
            HouseholdProfile incoming) {
        return runRoom(
                context,
                database -> {
                    boolean[] result =
                            new boolean[1];

                    database.runInTransaction(
                            () ->
                                    result[0] =
                                            upsert(
                                                    database.householdProfileDao(),
                                                    incoming));

                    return result[0];
                });
    }

    static boolean removeProfile(
            Context context,
            String profileId) {
        return runRoom(
                context,
                database ->
                        removeProfile(
                                database.householdProfileDao(),
                                profileId));
    }

    static int removeOwnerExcept(
            Context context,
            String ownerDeviceId,
            List<String> retainedProfileIds) {
        return runRoom(
                context,
                database -> {
                    int[] result =
                            new int[1];

                    database.runInTransaction(
                            () ->
                                    result[0] =
                                            removeOwnerExcept(
                                                    database.householdProfileDao(),
                                                    ownerDeviceId,
                                                    retainedProfileIds));

                    return result[0];
                });
    }

    static int removeOwner(
            Context context,
            String ownerDeviceId) {
        return runRoom(
                context,
                database ->
                        removeOwner(
                                database.householdProfileDao(),
                                ownerDeviceId));
    }

    static List<HouseholdProfile> active(
            Context context) {
        return runRoom(
                context,
                database ->
                        active(
                                database.householdProfileDao()));
    }

    static int count(
            Context context) {
        return runRoom(
                context,
                database ->
                        database.householdProfileDao()
                                .count());
    }

    static List<HouseholdProfile> load(
            HouseholdProfileDao dao) {
        if (dao == null) {
            throw new IllegalArgumentException(
                    "Household profile DAO is required");
        }

        List<HouseholdProfile> result =
                new ArrayList<>();

        for (HouseholdProfileEntity entity :
                dao.loadAll()) {
            result.add(
                    fromEntity(
                            entity));
        }

        return result;
    }

    static HouseholdProfile find(
            HouseholdProfileDao dao,
            String profileId) {
        if (dao == null
                || profileId == null) {
            return null;
        }

        HouseholdProfileEntity entity =
                dao.find(
                        profileId);

        return entity == null
                ? null
                : fromEntity(entity);
    }

    static boolean upsert(
            HouseholdProfileDao dao,
            HouseholdProfile incoming) {
        if (dao == null
                || incoming == null
                || !incoming.isValid()) {
            return false;
        }

        HouseholdProfileEntity existing =
                dao.find(
                        incoming.profileId);

        if (existing != null) {
            HouseholdProfile stored =
                    fromEntity(
                            existing);

            if (!stored.ownerDeviceId.equals(
                    incoming.ownerDeviceId)) {
                return false;
            }

            if (stored.updatedAtMs
                    >= incoming.updatedAtMs) {
                return false;
            }

            HouseholdProfileEntity replacement =
                    toEntity(
                            incoming,
                            existing.sortOrder);

            if (dao.update(
                    replacement) != 1) {
                throw new IllegalStateException(
                        "Household profile update failed");
            }

            return true;
        }

        HouseholdProfileEntity created =
                toEntity(
                        incoming,
                        nextSortOrder(
                                dao));

        long inserted =
                dao.insert(
                        created);

        if (inserted != -1L) {
            return true;
        }

        existing =
                dao.find(
                        incoming.profileId);

        if (existing == null) {
            throw new IllegalStateException(
                    "Household profile insert failed");
        }

        HouseholdProfile stored =
                fromEntity(
                        existing);

        if (!stored.ownerDeviceId.equals(
                incoming.ownerDeviceId)
                || stored.updatedAtMs
                        >= incoming.updatedAtMs) {
            return false;
        }

        HouseholdProfileEntity replacement =
                toEntity(
                        incoming,
                        existing.sortOrder);

        if (dao.update(
                replacement) != 1) {
            throw new IllegalStateException(
                    "Household profile update after insert race failed");
        }

        return true;
    }

    static boolean removeProfile(
            HouseholdProfileDao dao,
            String profileId) {
        if (dao == null
                || profileId == null) {
            return false;
        }

        return dao.delete(
                profileId) == 1;
    }

    static int removeOwnerExcept(
            HouseholdProfileDao dao,
            String ownerDeviceId,
            List<String> retainedProfileIds) {
        if (dao == null
                || !PeerTrustStore.isValidDeviceId(
                        ownerDeviceId)
                || retainedProfileIds == null) {
            return 0;
        }

        Set<String> retained =
                new HashSet<>();

        for (String profileId :
                retainedProfileIds) {
            if (!UserProfile.isValidHouseholdProfileId(
                    profileId)) {
                return 0;
            }

            retained.add(
                    profileId);
        }

        int removed =
                0;

        for (HouseholdProfileEntity entity :
                dao.loadAll()) {
            if (!ownerDeviceId.equals(
                    entity.ownerDeviceId)
                    || retained.contains(
                            entity.profileId)) {
                continue;
            }

            int deleted =
                    dao.delete(
                            entity.profileId);

            if (deleted != 1) {
                throw new IllegalStateException(
                        "Household profile manifest cleanup failed");
            }

            removed++;
        }

        return removed;
    }

    static int removeOwner(
            HouseholdProfileDao dao,
            String ownerDeviceId) {
        if (dao == null
                || ownerDeviceId == null) {
            return 0;
        }

        return dao.deleteOwner(
                ownerDeviceId);
    }

    static List<HouseholdProfile> active(
            HouseholdProfileDao dao) {
        List<HouseholdProfile> result =
                new ArrayList<>();

        for (HouseholdProfile profile :
                load(
                        dao)) {
            if (profile.active) {
                result.add(
                        profile);
            }
        }

        return result;
    }

    static boolean migrateLegacyForTest(
            SharedPreferences preferences,
            HouseholdProfileDao dao) {
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
                                            "Household profile migration failed");
                                }

                                return operation.run(
                                        database);
                            })
                    .get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Household profile Room operation interrupted",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause =
                    exception.getCause();

            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }

            throw new IllegalStateException(
                    "Household profile Room operation failed",
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
                                database.householdProfileDao(),
                                legacy)) {
                            throw new IllegalStateException(
                                    "Household profile migration conflict");
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

            Map<String, HouseholdProfileEntity> byId =
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

                HouseholdProfile profile =
                        HouseholdProfile.fromJson(
                                object);

                if (profile == null
                        || byId.containsKey(
                                profile.profileId)) {
                    return invalidLegacy();
                }

                byId.put(
                        profile.profileId,
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
            HouseholdProfileDao dao,
            LegacyData legacy) {
        if (dao == null
                || legacy == null
                || !legacy.valid) {
            return false;
        }

        for (HouseholdProfileEntity incoming :
                legacy.items) {
            HouseholdProfileEntity existing =
                    dao.find(
                            incoming.profileId);

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
                            incoming.profileId);

            if (existing == null
                    || !sameStoredData(
                            existing,
                            incoming)) {
                return false;
            }
        }

        return true;
    }

    private static LegacyData invalidLegacy() {
        return new LegacyData(
                false,
                List.of());
    }

    private static HouseholdProfileEntity toEntity(
            HouseholdProfile profile,
            long sortOrder) {
        return new HouseholdProfileEntity(
                profile.profileId,
                profile.name,
                profile.ownerDeviceId,
                profile.referenceWeightKg,
                profile.toleranceKg,
                profile.active,
                profile.updatedAtMs,
                sortOrder);
    }

    private static HouseholdProfile fromEntity(
            HouseholdProfileEntity entity) {
        HouseholdProfile profile =
                new HouseholdProfile(
                        entity.profileId,
                        entity.name,
                        entity.ownerDeviceId,
                        entity.referenceWeightKg,
                        entity.toleranceKg,
                        entity.active,
                        entity.updatedAtMs);

        if (!profile.isValid()
                || entity.sortOrder < 0L) {
            throw new IllegalStateException(
                    "Invalid household profile Room row");
        }

        return profile;
    }

    private static long nextSortOrder(
            HouseholdProfileDao dao) {
        Long maximum =
                dao.maxSortOrder();

        if (maximum == null) {
            return 0L;
        }

        if (maximum == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "Household profile sort order exhausted");
        }

        return maximum + 1L;
    }

    private static boolean sameStoredData(
            HouseholdProfileEntity first,
            HouseholdProfileEntity second) {
        return first.profileId.equals(
                        second.profileId)
                && first.name.equals(
                        second.name)
                && first.ownerDeviceId.equals(
                        second.ownerDeviceId)
                && Float.compare(
                        first.referenceWeightKg,
                        second.referenceWeightKg) == 0
                && Float.compare(
                        first.toleranceKg,
                        second.toleranceKg) == 0
                && first.active
                        == second.active
                && first.updatedAtMs
                        == second.updatedAtMs
                && first.sortOrder
                        == second.sortOrder;
    }
}
