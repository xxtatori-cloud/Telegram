package org.telegram.messenger.monogram;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MonogramConfig {
    private static final String PREFS_NAME = "monogram_config";
    private static final String KEY_OWNER_ID = "owner_id";
    private static final String KEY_USERS = "registered_users";
    private static final String KEY_DEV_MODE = "dev_mode_enabled";
    private static final String KEY_ACTIVE_USER_ID_PREFIX = "active_local_user_id_";

    private static volatile MonogramConfig instance;
    private final SharedPreferences preferences;
    private long ownerUserId = 0;
    private boolean devModeEnabled = false;
    private final List<MonogramUserRecord> registeredUsers = new ArrayList<>();

    public static class MonogramUserRecord {
        public long userId;
        public String firstName;
        public String lastName;
        public String username;
        public String phone;
        public long createdAt;
        public boolean isOwner;

        public MonogramUserRecord() {}

        public MonogramUserRecord(long userId, String firstName, String lastName, String username, String phone, long createdAt, boolean isOwner) {
            this.userId = userId;
            this.firstName = firstName;
            this.lastName = lastName;
            this.username = username;
            this.phone = phone;
            this.createdAt = createdAt;
            this.isOwner = isOwner;
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("userId", userId);
                obj.put("firstName", firstName != null ? firstName : "");
                obj.put("lastName", lastName != null ? lastName : "");
                obj.put("username", username != null ? username : "");
                obj.put("phone", phone != null ? phone : "");
                obj.put("createdAt", createdAt);
                obj.put("isOwner", isOwner);
            } catch (Exception e) {
                FileLog.e(e);
            }
            return obj;
        }

        public static MonogramUserRecord fromJson(JSONObject obj) {
            if (obj == null) return null;
            MonogramUserRecord record = new MonogramUserRecord();
            record.userId = obj.optLong("userId", 0);
            record.firstName = obj.optString("firstName", "");
            record.lastName = obj.optString("lastName", "");
            record.username = obj.optString("username", "");
            record.phone = obj.optString("phone", "");
            record.createdAt = obj.optLong("createdAt", System.currentTimeMillis());
            record.isOwner = obj.optBoolean("isOwner", false);
            return record;
        }
    }

    public static MonogramConfig getInstance() {
        if (instance == null) {
            synchronized (MonogramConfig.class) {
                if (instance == null) {
                    instance = new MonogramConfig();
                }
            }
        }
        return instance;
    }

    private MonogramConfig() {
        Context context = ApplicationLoader.applicationContext;
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
    }

    private synchronized void load() {
        ownerUserId = preferences.getLong(KEY_OWNER_ID, 0);
        devModeEnabled = preferences.getBoolean(KEY_DEV_MODE, true);
        registeredUsers.clear();
        String usersJson = preferences.getString(KEY_USERS, null);
        if (usersJson != null) {
            try {
                JSONArray arr = new JSONArray(usersJson);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    MonogramUserRecord r = MonogramUserRecord.fromJson(obj);
                    if (r != null) {
                        registeredUsers.add(r);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private synchronized void saveUsers() {
        try {
            JSONArray arr = new JSONArray();
            for (MonogramUserRecord r : registeredUsers) {
                arr.put(r.toJson());
            }
            preferences.edit().putString(KEY_USERS, arr.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Stores which local MONOGRAM profile is active for a Telegram account slot.
     * commit() is intentional: this marker is the recovery source if UserConfig's
     * deferred save has not completed when Android kills the process.
     */
    public synchronized void setActiveUserId(int account, long userId) {
        preferences.edit().putLong(KEY_ACTIVE_USER_ID_PREFIX + account, userId).commit();
    }

    public synchronized long getActiveUserId(int account) {
        return preferences.getLong(KEY_ACTIVE_USER_ID_PREFIX + account, 0);
    }

    public synchronized void clearActiveUserId(int account) {
        preferences.edit().remove(KEY_ACTIVE_USER_ID_PREFIX + account).commit();
    }

    public synchronized MonogramUserRecord getUserRecord(long userId) {
        for (MonogramUserRecord record : registeredUsers) {
            if (record.userId == userId) {
                return new MonogramUserRecord(record.userId, record.firstName, record.lastName,
                        record.username, record.phone, record.createdAt, record.isOwner);
            }
        }
        return null;
    }

    public synchronized long getOwnerUserId() {
        return ownerUserId;
    }

    public synchronized boolean hasOwner() {
        return ownerUserId != 0;
    }

    public synchronized boolean isOwner(long userId) {
        return userId != 0 && ownerUserId != 0 && userId == ownerUserId;
    }

    public synchronized void setInitialOwnerIfNone(long userId) {
        if (ownerUserId == 0 && userId != 0) {
            ownerUserId = userId;
            preferences.edit().putLong(KEY_OWNER_ID, ownerUserId).apply();
        }
    }

    public synchronized void registerUser(MonogramUserRecord user) {
        if (user == null || user.userId == 0) return;
        boolean found = false;
        for (int i = 0; i < registeredUsers.size(); i++) {
            if (registeredUsers.get(i).userId == user.userId) {
                registeredUsers.set(i, user);
                found = true;
                break;
            }
        }
        if (!found) {
            registeredUsers.add(user);
        }
        saveUsers();
    }

    public synchronized List<MonogramUserRecord> getRegisteredUsers() {
        return Collections.unmodifiableList(new ArrayList<>(registeredUsers));
    }

    public synchronized int getRegisteredUsersCount() {
        return registeredUsers.size();
    }

    public synchronized boolean isDevModeEnabled() {
        return devModeEnabled;
    }

    public synchronized void setDevModeEnabled(boolean enabled) {
        devModeEnabled = enabled;
        preferences.edit().putBoolean(KEY_DEV_MODE, enabled).apply();
    }

    public synchronized void resetDevelopmentData() {
        registeredUsers.clear();
        preferences.edit().remove(KEY_USERS).apply();
    }
}
