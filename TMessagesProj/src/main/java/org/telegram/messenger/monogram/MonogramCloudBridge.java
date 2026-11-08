package org.telegram.messenger.monogram;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

/**
 * Adapts Supabase identities and messages to the Telegram-derived UI model.
 * The transport remains MONOGRAM/Supabase; no MTProto session is created.
 */
public final class MonogramCloudBridge {
    private MonogramCloudBridge() {}

    public static boolean isActive(int account) {
        return MonogramBackend.isConfigured() && MonogramConfig.getInstance().isMonogramAccount(account);
    }

    public static void loadDirectoryUsers(int account) {
        MonogramBackend.loadDirectory(org.telegram.messenger.ApplicationLoader.applicationContext,
                new MonogramBackend.Callback<JSONArray>() {
                    @Override
                    public void onSuccess(JSONArray profiles) {
                        ArrayList<TLRPC.User> users = new ArrayList<>();
                        for (int i = 0; profiles != null && i < profiles.length(); i++) {
                            try {
                                TLRPC.User user = userFromProfile(account, profiles.getJSONObject(i));
                                if (user != null) users.add(user);
                            } catch (Exception ignore) {
                            }
                        }
                        org.telegram.messenger.ContactsController.getInstance(account).addMonogramContacts(users);
                    }

                    @Override
                    public void onError(String error) {
                        org.telegram.messenger.FileLog.e("MONOGRAM directory load failed: " + error);
                    }
                });
    }

    public static TLRPC.User userFromProfile(int account, JSONObject profile) {
        if (profile == null) return null;
        String backendId = profile.optString("user_id", "");
        if (backendId.isEmpty()) return null;

        MonogramConfig config = MonogramConfig.getInstance();
        MonogramConfig.MonogramUserRecord record = config.getUserRecordByBackendId(backendId);
        if (record == null) {
            long localId;
            try {
                localId = UUID.fromString(backendId).getMostSignificantBits() & Long.MAX_VALUE;
            } catch (Exception e) {
                localId = (long) (backendId.hashCode() & 0x7fffffff) + 2000000000L;
            }
            if (localId == 0 || localId == UserConfig.getInstance(account).getClientUserId()) localId += 11;
            record = new MonogramConfig.MonogramUserRecord(
                    localId,
                    profile.optString("first_name", ""),
                    profile.optString("last_name", ""),
                    profile.optString("username", ""),
                    "",
                    System.currentTimeMillis(),
                    profile.optBoolean("is_owner", false)
            );
            record.backendUserId = backendId;
            config.registerUser(record);
        }

        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = record.userId;
        user.first_name = record.firstName == null ? "" : record.firstName;
        user.last_name = record.lastName == null ? "" : record.lastName;
        user.username = record.username == null ? "" : record.username;
        user.self = user.id == UserConfig.getInstance(account).getClientUserId();
        user.flags = 2 | 4 | 8 | 64;
        if (user.self) user.flags |= 1024;
        if (profile.optBoolean("premium_enabled", true)) {
            user.premium = true;
            user.flags |= 268435456;
        }
        user.status = new TLRPC.TL_userStatusRecently();
        user.photo = new TLRPC.TL_userProfilePhotoEmpty();
        MessagesController.getInstance(account).putUser(user, false);
        ArrayList<TLRPC.User> users = new ArrayList<>();
        users.add(user);
        MessagesStorage.getInstance(account).putUsersAndChats(users, null, true, true);
        return user;
    }

    public static TLRPC.Message messageFromJson(int account, long dialogId, JSONObject json) {
        if (json == null || dialogId <= 0) return null;
        String senderId = json.optString("sender_id", "");
        String selfBackendId = MonogramBackend.getCurrentUserId(
                org.telegram.messenger.ApplicationLoader.applicationContext);
        long selfId = UserConfig.getInstance(account).getClientUserId();
        long peerId = dialogId;
        boolean outgoing = senderId.equals(selfBackendId);

        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = (json.optString("id", "").hashCode() & 0x7fffffff) + 1;
        message.dialog_id = dialogId;
        message.message = json.optString("body", "");
        message.date = parseDate(json.optString("created_at", ""));
        message.out = outgoing;
        message.unread = !outgoing;
        message.media_unread = false;
        message.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = outgoing ? selfId : peerId;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = peerId;
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.entities = new ArrayList<>();
        return message;
    }

    private static int parseDate(String value) {
        try {
            return (int) Instant.parse(value).getEpochSecond();
        } catch (Exception ignore) {
            return (int) (System.currentTimeMillis() / 1000L);
        }
    }

    public static TLRPC.TL_messages_dialogs buildDialogs(int account, JSONArray chats) {
        TLRPC.TL_messages_dialogs result = new TLRPC.TL_messages_dialogs();
        HashMap<Long, TLRPC.User> users = new HashMap<>();
        for (int i = 0; chats != null && i < chats.length(); i++) {
            try {
                JSONObject chat = chats.getJSONObject(i);
                if (chat.optBoolean("is_group", false)) continue;
                String chatId = chat.optString("id", "");
                JSONArray members = chat.optJSONArray("members");
                if (members == null || members.length() == 0) continue;
                JSONObject peerProfile = members.getJSONObject(0);
                TLRPC.User peer = userFromProfile(account, peerProfile);
                if (peer == null) continue;
                users.put(peer.id, peer);

                TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
                dialog.id = peer.id;
                dialog.peer = new TLRPC.TL_peerUser();
                dialog.peer.user_id = peer.id;
                dialog.folder_id = 0;
                dialog.unread_count = 0;
                dialog.unread_mentions_count = 0;
                dialog.unread_reactions_count = 0;
                dialog.read_inbox_max_id = 0;
                dialog.read_outbox_max_id = 0;
                dialog.notify_settings = new TLRPC.TL_peerNotifySettings();

                JSONArray messages = chat.optJSONArray("messages");
                if (messages != null && messages.length() > 0) {
                    TLRPC.Message last = messageFromJson(account, peer.id, messages.getJSONObject(0));
                    if (last != null) {
                        dialog.top_message = last.id;
                        dialog.last_message_date = last.date;
                        result.messages.add(last);
                    }
                }
                MonogramConfig.getInstance().setBackendChatId(dialog.id, chatId);
                result.dialogs.add(dialog);
            } catch (Exception ignore) {
            }
        }
        result.users.addAll(users.values());
        result.count = result.dialogs.size();
        return result;
    }

    public static void sendTextMessage(int account, long dialogId, String body,
                                       MonogramBackend.Callback<MonogramBackend.Message> callback) {
        Context context = org.telegram.messenger.ApplicationLoader.applicationContext;
        MonogramConfig config = MonogramConfig.getInstance();
        String chatId = config.getBackendChatId(dialogId);
        if (chatId != null && !chatId.isEmpty()) {
            MonogramBackend.sendMessage(context, chatId, body, callback);
            return;
        }
        String remotePeerId = config.getBackendUserId(dialogId);
        if (remotePeerId == null || remotePeerId.isEmpty()) {
            if (callback != null) callback.onError("This MONOGRAM chat is not linked to a cloud account yet.");
            return;
        }
        MonogramBackend.createDirectChat(context, remotePeerId, new MonogramBackend.Callback<String>() {
            @Override
            public void onSuccess(String newChatId) {
                config.setBackendChatId(dialogId, newChatId);
                MonogramBackend.sendMessage(context, newChatId, body, callback);
            }

            @Override
            public void onError(String message) {
                if (callback != null) callback.onError(message);
            }
        });
    }

    public static void storeAndNotifyMessages(int account, long dialogId, ArrayList<TLRPC.Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        AndroidUtilities.runOnUIThread(() -> {
            ArrayList<MessageObject> objects = new ArrayList<>();
            for (TLRPC.Message message : messages) {
                objects.add(new MessageObject(account, message, false, false));
            }
            MessagesStorage.getInstance(account).putMessages(messages, true, true, false, 0, 0, 0);
            MessagesController.getInstance(account).updateInterfaceWithMessages(dialogId, objects, 0);
        });
    }

    public static ArrayList<TLRPC.Message> convertMessages(int account, long dialogId, java.util.List<MonogramBackend.Message> messages) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        if (messages == null) return result;
        for (MonogramBackend.Message message : messages) {
            try {
                JSONObject json = new JSONObject()
                        .put("id", message.id)
                        .put("sender_id", message.senderId)
                        .put("body", message.body)
                        .put("created_at", message.createdAt);
                TLRPC.Message converted = messageFromJson(account, dialogId, json);
                if (converted != null) result.add(converted);
            } catch (Exception ignore) {
            }
        }
        return result;
    }
}
