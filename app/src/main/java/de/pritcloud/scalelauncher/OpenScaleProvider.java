package de.pritcloud.scalelauncher;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

public final class OpenScaleProvider {
    private static final String[] AUTHORITIES = {
            "com.health.openscale.provider",
            "com.health.openscale.oss.provider",
            "com.health.openscale.beta.provider",
            "com.health.openscale.debug.provider"
    };

    public static final class User {
        public final long id;
        public final String name;
        User(long id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name + " (#" + id + ")"; }
    }

    private OpenScaleProvider() {}

    public static String findAuthority(Context context) {
        for (String authority : AUTHORITIES) {
            ProviderInfo info = context.getPackageManager().resolveContentProvider(authority, 0);
            if (info != null) return authority;
        }
        return null;
    }

    public static String permissionForAuthority(String authority) {
        if (authority == null || !authority.endsWith(".provider")) return null;
        return authority.substring(0, authority.length() - ".provider".length()) + ".READ_WRITE_DATA";
    }

    public static List<User> loadUsers(Context context, String authority) {
        List<User> users = new ArrayList<>();
        Uri uri = Uri.parse("content://" + authority + "/users");
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{"_ID", "username"}, null, null, null)) {
            if (cursor == null) return users;
            int idColumn = cursor.getColumnIndexOrThrow("_ID");
            int nameColumn = cursor.getColumnIndexOrThrow("username");
            while (cursor.moveToNext()) users.add(new User(cursor.getLong(idColumn), cursor.getString(nameColumn)));
        }
        return users;
    }

    public static boolean insertMeasurement(Context context, String authority, long userId, long timestamp,
                                            float weightKg, Float fat, Float water, Float muscle) {
        ContentValues values = new ContentValues();
        values.put("datetime", timestamp);
        values.put("weight", weightKg);
        if (fat != null) values.put("fat", fat);
        if (water != null) values.put("water", water);
        if (muscle != null) values.put("muscle", muscle);
        Uri uri = Uri.parse("content://" + authority + "/measurements/" + userId);
        ContentResolver resolver = context.getContentResolver();
        resolver.insert(uri, values);
        // openScale currently returns null for compatibility even after a successful insert.
        return true;
    }
}
