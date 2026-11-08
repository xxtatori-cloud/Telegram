package org.telegram.messenger.monogram;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Random;

public class MonogramAuthManager {

    public interface AuthCallback {
        void onSuccess(TLRPC.User user, boolean isOwner);
        void onError(String error);
    }

    /**
     * Generates a unique positive 64-bit ID for a local Monogram user.
     */
    public static long generateUniqueUserId() {
        long baseId = 7000000000L;
        long randomSuffix = (long) (new Random().nextDouble() * 900000000L);
        long candidate = baseId + randomSuffix;

        MonogramConfig config = MonogramConfig.getInstance();
        boolean unique = false;
        while (!unique) {
            unique = true;
            for (MonogramConfig.MonogramUserRecord r : config.getRegisteredUsers()) {
                if (r.userId == candidate) {
                    candidate++;
                    unique = false;
                    break;
                }
            }
        }
        return candidate;
    }

    /**
     * Creates or restores the real Supabase-backed MONOGRAM identity first,
     * then installs the profile in the Telegram-derived local UI shell.
     */
    public static void createAccount(
            int currentAccount,
            String firstName,
            String lastName,
            String username,
            String displayPhone,
            TLRPC.FileLocation avatarLocation,
            AuthCallback callback
    ) {
        MonogramBackend.registerOrRestore(ApplicationLoader.applicationContext, firstName, lastName, username,
                new MonogramBackend.Callback<MonogramBackend.Session>() {
                    @Override
                    public void onSuccess(MonogramBackend.Session session) {
                        JSONObjectProfile profile = new JSONObjectProfile(session.profile);
                        String serverFirst = profile.firstName.isEmpty() ? firstName : profile.firstName;
                        String serverLast = profile.lastName.isEmpty() ? lastName : profile.lastName;
                        String serverUsername = profile.username.isEmpty() ? session.getUsername() : profile.username;
                        String monogramNumber = profile.monogramNumber;
                        createLocalAccount(currentAccount, serverFirst, serverLast, serverUsername,
                                monogramNumber, avatarLocation, session.isOwner(), session.userId, callback);
                    }

                    @Override
                    public void onError(String error) {
                        if (callback != null) callback.onError(error);
                    }
                });
    }

    private static final class JSONObjectProfile {
        final String firstName;
        final String lastName;
        final String username;
        final String monogramNumber;
        JSONObjectProfile(org.json.JSONObject json) {
            firstName = json == null ? "" : json.optString("first_name", "");
            lastName = json == null ? "" : json.optString("last_name", "");
            username = json == null ? "" : json.optString("username", "");
            monogramNumber = json == null ? "" : json.optString("monogram_number", "");
        }
    }

    /**
     * Installs an already-authenticated Supabase profile in the Telegram-derived
     * local UI model. This is not a Telegram MTProto session.
     */
    private static void createLocalAccount(
            int currentAccount,
            String firstName,
            String lastName,
            String username,
            String displayPhone,
            TLRPC.FileLocation avatarLocation,
            boolean serverOwner,
            String backendUserId,
            AuthCallback callback
    ) {
        if (firstName == null || firstName.trim().isEmpty()) {
            if (callback != null) {
                callback.onError("First name cannot be empty");
            }
            return;
        }

        firstName = firstName.trim();
        lastName = lastName != null ? lastName.trim() : "";
        username = username != null ? username.trim().replace("@", "") : "";
        displayPhone = displayPhone != null ? displayPhone.trim().replaceAll("[^A-Za-z0-9+_-]", "") : "";

        long newUserId = generateUniqueUserId();
        MonogramConfig config = MonogramConfig.getInstance();

        // Check if this is the first MONOGRAM account ever created
        boolean isOwner = serverOwner;
        if (isOwner) {
            config.setOwnerUserId(newUserId);
        }

        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = newUserId;
        user.first_name = firstName;
        user.last_name = lastName;
        user.username = username;
        user.phone = displayPhone;
        user.self = true;
        user.flags |= 1024; // FLAG_10 = self

        // MONOGRAM owner verification is stored in MONOGRAM metadata and
        // must not impersonate Telegram's official verification flag.

        // Apply Free Premium if enabled in MONOGRAM economy
        if (MonogramEconomy.getInstance().isPremiumEnabled()) {
            user.premium = true;
            user.flags |= 268435456; // FLAG_28 = premium
        }

        user.status = new TLRPC.TL_userStatusOnline();
        user.status.expires = Integer.MAX_VALUE;

        // Setup profile photo if available
        if (avatarLocation != null) {
            TLRPC.TL_userProfilePhoto photo = new TLRPC.TL_userProfilePhoto();
            photo.photo_id = newUserId;
            photo.photo_small = avatarLocation;
            photo.photo_big = avatarLocation;
            user.photo = photo;
            user.flags |= 32; // FLAG_5 = photo
        } else {
            user.photo = new TLRPC.TL_userProfilePhotoEmpty();
        }

        // Register in MonogramConfig registry
        MonogramConfig.MonogramUserRecord record = new MonogramConfig.MonogramUserRecord(
                newUserId,
                firstName,
                lastName,
                username,
                displayPhone,
                System.currentTimeMillis(),
                isOwner
        );
        record.backendUserId = backendUserId == null ? "" : backendUserId;
        config.registerUser(record);

        // Populate and activate in Telegram subsystems
        final TLRPC.User finalUser = user;
        final boolean finalIsOwner = isOwner;

        AndroidUtilities.runOnUIThread(() -> {
            try {
                MessagesController mc = MessagesController.getInstance(currentAccount);
                UserConfig uc = UserConfig.getInstance(currentAccount);
                MessagesStorage ms = MessagesStorage.getInstance(currentAccount);

                // Reset the account slot once before installing the new local profile.
                // The previous double cleanup needlessly reset controller state twice.
                uc.clearConfig();
                mc.cleanup();

                uc.setCurrentUser(finalUser);
                config.setActiveUserId(currentAccount, finalUser.id);
                uc.saveConfig(true);

                ms.cleanup(true);
                ArrayList<TLRPC.User> users = new ArrayList<>();
                users.add(finalUser);
                ms.putUsersAndChats(users, null, true, true);
                mc.putUser(finalUser, false);

                ContactsController.getInstance(currentAccount).checkAppAccount();
                MonogramCloudBridge.loadDirectoryUsers(currentAccount);
                mc.loadAppConfig();
                mc.putDialogsEndReachedAfterRegistration();
                MediaDataController.getInstance(currentAccount).loadStickersByEmojiOrName(
                        AndroidUtilities.STICKERS_PLACEHOLDER_PACK_NAME, false, true
                );

                NotificationCenter.getInstance(currentAccount).postNotificationName(
                        NotificationCenter.mainUserInfoChanged
                );

                if (callback != null) {
                    callback.onSuccess(finalUser, finalIsOwner);
                }
            } catch (Exception e) {
                FileLog.e(e);
                if (callback != null) {
                    callback.onError(e.getMessage() != null ? e.getMessage() : "Error initializing account");
                }
            }
        });
    }

    /**
     * Restores a local MONOGRAM profile when Telegram's deferred UserConfig write was
     * interrupted by process death. This restores only the local profile; it does not
     * create or impersonate a Telegram server session.
     */
    public static boolean restoreLocalAccountIfNeeded(int account) {
        UserConfig userConfig = UserConfig.getInstance(account);
        if (userConfig.isClientActivated()) {
            return false;
        }

        MonogramConfig config = MonogramConfig.getInstance();
        long activeUserId = config.getActiveUserId(account);
        if (activeUserId == 0) {
            return false;
        }

        MonogramConfig.MonogramUserRecord record = config.getUserRecord(activeUserId);
        if (record == null) {
            config.clearActiveUserId(account);
            return false;
        }

        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = record.userId;
        user.first_name = record.firstName != null ? record.firstName : "";
        user.last_name = record.lastName != null ? record.lastName : "";
        user.username = record.username != null ? record.username : "";
        user.phone = record.phone != null ? record.phone : "";
        user.self = true;
        user.flags |= 1024;
        // The MONOGRAM owner badge is rendered by MONOGRAM UI, not Telegram's
        // official verified flag.
        if (MonogramEconomy.getInstance().isPremiumEnabled()) {
            user.premium = true;
            user.flags |= 268435456;
        }
        user.status = new TLRPC.TL_userStatusOnline();
        user.status.expires = Integer.MAX_VALUE;
        user.photo = new TLRPC.TL_userProfilePhotoEmpty();

        userConfig.setCurrentUser(user);
        userConfig.saveConfig(true);
        MonogramCloudBridge.loadDirectoryUsers(account);
        FileLog.d("MONOGRAM restored local profile " + activeUserId + " for account slot " + account);
        return true;
    }

    /**
     * Checks if the active user of the specified account is the designated Monogram Owner.
     */
    public static boolean isCurrentAccountOwner(int account) {
        long currentId = UserConfig.getInstance(account).getClientUserId();
        return MonogramConfig.getInstance().isOwner(currentId);
    }
}
