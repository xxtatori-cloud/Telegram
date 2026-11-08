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
     * Creates and activates a new MONOGRAM local development account.
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
        if (firstName == null || firstName.trim().isEmpty()) {
            if (callback != null) {
                callback.onError("First name cannot be empty");
            }
            return;
        }

        firstName = firstName.trim();
        lastName = lastName != null ? lastName.trim() : "";
        username = username != null ? username.trim().replace("@", "") : "";
        displayPhone = displayPhone != null ? displayPhone.trim().replaceAll("[^0-9+]", "") : "";

        long newUserId = generateUniqueUserId();
        MonogramConfig config = MonogramConfig.getInstance();

        // Check if this is the first MONOGRAM account ever created
        boolean isOwner = !config.hasOwner();
        if (isOwner) {
            config.setInitialOwnerIfNone(newUserId);
        }

        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = newUserId;
        user.first_name = firstName;
        user.last_name = lastName;
        user.username = username;
        user.phone = displayPhone;
        user.self = true;
        user.flags |= 1024; // FLAG_10 = self

        // Owner receives custom MONOGRAM verification badge
        if (isOwner) {
            user.verified = true;
            user.flags |= 131072; // FLAG_17 = verified
        }

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
        config.registerUser(record);

        // Populate and activate in Telegram subsystems
        final TLRPC.User finalUser = user;
        final boolean finalIsOwner = isOwner;

        AndroidUtilities.runOnUIThread(() -> {
            try {
                MessagesController mc = MessagesController.getInstance(currentAccount);
                UserConfig uc = UserConfig.getInstance(currentAccount);
                MessagesStorage ms = MessagesStorage.getInstance(currentAccount);

                mc.cleanup();
                uc.clearConfig();
                mc.cleanup();

                uc.setCurrentUser(finalUser);
                uc.saveConfig(true);

                ms.cleanup(true);
                ArrayList<TLRPC.User> users = new ArrayList<>();
                users.add(finalUser);
                ms.putUsersAndChats(users, null, true, true);
                mc.putUser(finalUser, false);

                ContactsController.getInstance(currentAccount).checkAppAccount();
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
     * Checks if the active user of the specified account is the designated Monogram Owner.
     */
    public static boolean isCurrentAccountOwner(int account) {
        long currentId = UserConfig.getInstance(account).getClientUserId();
        return MonogramConfig.getInstance().isOwner(currentId);
    }
}
