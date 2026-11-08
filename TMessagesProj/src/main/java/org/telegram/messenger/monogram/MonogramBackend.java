package org.telegram.messenger.monogram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Supabase client for MONOGRAM.
 *
 * Uses only the public anon/publishable key in the APK. All authorization is
 * enforced by Postgres RLS; never place a service-role key in this application.
 */
public final class MonogramBackend {
    private static final String PREFS = "monogram_backend_session";
    private static final String ACCESS = "access_token";
    private static final String REFRESH = "refresh_token";
    private static final String USER_ID = "user_id";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MonogramBackend() {}

    public interface Callback<T> {
        void onSuccess(T value);
        void onError(String message);
    }

    public static final class Session {
        public final String userId;
        public final String accessToken;
        public final String refreshToken;
        public final JSONObject profile;

        Session(String userId, String accessToken, String refreshToken, JSONObject profile) {
            this.userId = userId;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.profile = profile;
        }

        public boolean isOwner() {
            return profile != null && profile.optBoolean("is_owner", false);
        }

        public String getUsername() {
            return profile == null ? "" : profile.optString("username", "");
        }

        public String getMonogramNumber() {
            return profile == null ? "" : profile.optString("monogram_number", "");
        }
    }

    public static final class Message {
        public final String id;
        public final String chatId;
        public final String senderId;
        public final String body;
        public final String createdAt;

        Message(JSONObject json) {
            id = json.optString("id", "");
            chatId = json.optString("chat_id", "");
            senderId = json.optString("sender_id", "");
            body = json.optString("body", "");
            createdAt = json.optString("created_at", "");
        }
    }

    public static boolean isConfigured() {
        return BuildConfig.MONOGRAM_SUPABASE_URL != null
                && !BuildConfig.MONOGRAM_SUPABASE_URL.trim().isEmpty()
                && BuildConfig.MONOGRAM_SUPABASE_ANON_KEY != null
                && !BuildConfig.MONOGRAM_SUPABASE_ANON_KEY.trim().isEmpty();
    }

    private static String baseUrl() {
        return BuildConfig.MONOGRAM_SUPABASE_URL.replaceAll("/+$", "");
    }

    private static String apiKey() {
        return BuildConfig.MONOGRAM_SUPABASE_ANON_KEY;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void deliverError(Callback<?> callback, String message) {
        if (callback != null) MAIN.post(() -> callback.onError(message));
    }

    private static void deliverSuccess(Callback<Session> callback, Session value) {
        if (callback != null) MAIN.post(() -> callback.onSuccess(value));
    }

    private static JSONObject request(String method, String path, String bearer, JSONObject body, String prefer) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("MONOGRAM backend is not configured. Set MONOGRAM_SUPABASE_URL and MONOGRAM_SUPABASE_ANON_KEY.");
        }
        URL url = new URL(baseUrl() + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("apikey", apiKey());
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + (bearer == null || bearer.isEmpty() ? apiKey() : bearer));
        if (prefer != null && !prefer.isEmpty()) connection.setRequestProperty("Prefer", prefer);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder response = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
            }
        }
        connection.disconnect();
        String raw = response.toString();
        if (status < 200 || status >= 300) {
            String message = raw;
            try {
                JSONObject error = new JSONObject(raw);
                message = error.optString("msg", error.optString("message", error.optString("error_description", raw)));
            } catch (Exception ignore) {}
            throw new IllegalStateException("MONOGRAM server (" + status + "): " + message);
        }
        if (raw.isEmpty()) return new JSONObject();
        if (raw.startsWith("\"") || raw.equals("true") || raw.equals("false") || raw.matches("-?[0-9]+")) {
            JSONObject wrapper = new JSONObject();
            wrapper.put("_scalar", new org.json.JSONTokener(raw).nextValue().toString());
            return wrapper;
        }
        if (raw.startsWith("[")) {
            JSONObject wrapper = new JSONObject();
            wrapper.put("_array", new JSONArray(raw));
            return wrapper;
        }
        return new JSONObject(raw);
    }

    private static String cleanUsername(String value, String userId) {
        String cleaned = value == null ? "" : value.trim().replace("@", "").replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.length() < 5) {
            String suffix = userId == null ? Long.toHexString(System.nanoTime()) : userId.replace("-", "");
            cleaned = "user_" + suffix.substring(0, Math.min(12, suffix.length()));
        }
        if (cleaned.length() > 32) cleaned = cleaned.substring(0, 32);
        return cleaned;
    }

    /**
     * Restores the persisted Supabase session, or creates an anonymous MONOGRAM
     * account. The database trigger creates the profile and atomically assigns
     * the first account as owner.
     */
    public static void registerOrRestore(Context context, String firstName, String lastName,
                                         String requestedUsername, Callback<Session> callback) {
        IO.execute(() -> {
            try {
                if (!isConfigured()) throw new IllegalStateException(
                        "Supabase is not configured for this build. Add the MONOGRAM Supabase URL and anon key.");
                SharedPreferences p = prefs(context);
                String access = p.getString(ACCESS, "");
                String refresh = p.getString(REFRESH, "");
                String userId = p.getString(USER_ID, "");

                if (!access.isEmpty() && !userId.isEmpty()) {
                    try {
                        JSONObject profile = fetchProfile(access, userId);
                        Session session = new Session(userId, access, refresh, profile);
                        deliverSuccess(callback, session);
                        return;
                    } catch (Exception expired) {
                        if (!refresh.isEmpty()) {
                            JSONObject renewed = request("POST", "/auth/v1/token?grant_type=refresh_token", null,
                                    new JSONObject().put("refresh_token", refresh), null);
                            access = renewed.optString("access_token", "");
                            refresh = renewed.optString("refresh_token", refresh);
                            JSONObject authUser = renewed.optJSONObject("user");
                            userId = authUser == null ? userId : authUser.optString("id", userId);
                            saveSession(p, access, refresh, userId);
                            JSONObject profile = fetchProfile(access, userId);
                            deliverSuccess(callback, new Session(userId, access, refresh, profile));
                            return;
                        }
                    }
                }

                JSONObject metadata = new JSONObject();
                metadata.put("first_name", firstName == null ? "" : firstName.trim());
                metadata.put("last_name", lastName == null ? "" : lastName.trim());
                metadata.put("username", cleanUsername(requestedUsername, null));
                JSONObject signup = new JSONObject().put("data", metadata);
                JSONObject auth = request("POST", "/auth/v1/signup", null, signup, null);
                String newAccess = auth.optString("access_token", "");
                String newRefresh = auth.optString("refresh_token", "");
                JSONObject authUser = auth.optJSONObject("user");
                String newUserId = authUser == null ? "" : authUser.optString("id", "");
                if (newAccess.isEmpty() || newUserId.isEmpty()) {
                    throw new IllegalStateException("Supabase did not return an anonymous session. Check that Anonymous Sign-Ins are enabled.");
                }
                saveSession(p, newAccess, newRefresh, newUserId);
                JSONObject profile = fetchProfile(newAccess, newUserId);
                deliverSuccess(callback, new Session(newUserId, newAccess, newRefresh, profile));
            } catch (Exception e) {
                FileLog.e(e);
                deliverError(callback, e.getMessage() == null ? "MONOGRAM registration failed" : e.getMessage());
            }
        });
    }

    private static void saveSession(SharedPreferences p, String access, String refresh, String userId) {
        p.edit().putString(ACCESS, access).putString(REFRESH, refresh).putString(USER_ID, userId).commit();
    }

    private static JSONObject fetchProfile(String token, String userId) throws Exception {
        String path = "/rest/v1/profiles?select=*&user_id=eq." + URLEncoder.encode(userId, "UTF-8");
        JSONObject result = request("GET", path, token, null, null);
        JSONArray rows = result.optJSONArray("_array");
        if (rows == null || rows.length() == 0) {
            throw new IllegalStateException("MONOGRAM profile was not created by the database trigger. Apply the backend migration.");
        }
        return rows.getJSONObject(0);
    }

    public static void signOut(Context context) {
        prefs(context).edit().clear().commit();
    }

    public static String getCurrentUserId(Context context) {
        return prefs(context).getString(USER_ID, "");
    }

    public static String getAccessToken(Context context) {
        return prefs(context).getString(ACCESS, "");
    }

    public static void createDirectChat(Context context, String otherUserId, Callback<String> callback) {
        IO.execute(() -> {
            try {
                String token = getAccessToken(context);
                if (token.isEmpty()) throw new IllegalStateException("Sign in to MONOGRAM first.");
                JSONObject result = request("POST", "/rest/v1/rpc/create_direct_chat", token,
                        new JSONObject().put("p_other_user_id", otherUserId), null);
                String chatId = result.optString("_array", "");
                if (chatId.isEmpty()) chatId = result.optString("id", result.toString().replace("\"", ""));
                final String value = chatId;
                MAIN.post(() -> callback.onSuccess(value));
            } catch (Exception e) {
                FileLog.e(e);
                deliverError(callback, e.getMessage());
            }
        });
    }

    public static void sendMessage(Context context, String chatId, String body, Callback<Message> callback) {
        IO.execute(() -> {
            try {
                String token = getAccessToken(context);
                String userId = getCurrentUserId(context);
                if (token.isEmpty() || userId.isEmpty()) throw new IllegalStateException("Sign in to MONOGRAM first.");
                if (body == null || body.trim().isEmpty()) throw new IllegalArgumentException("Message is empty.");
                JSONObject payload = new JSONObject().put("chat_id", chatId)
                        .put("sender_id", userId).put("body", body.trim());
                JSONObject result = request("POST", "/rest/v1/messages?select=id,chat_id,sender_id,body,created_at",
                        token, payload, "return=representation");
                JSONArray rows = result.optJSONArray("_array");
                if (rows == null || rows.length() == 0) throw new IllegalStateException("Server did not save the message.");
                Message message = new Message(rows.getJSONObject(0));
                MAIN.post(() -> callback.onSuccess(message));
            } catch (Exception e) {
                FileLog.e(e);
                deliverError(callback, e.getMessage());
            }
        });
    }

    public static void loadMessages(Context context, String chatId, Callback<java.util.List<Message>> callback) {
        IO.execute(() -> {
            try {
                String token = getAccessToken(context);
                String path = "/rest/v1/messages?select=id,chat_id,sender_id,body,created_at"
                        + "&chat_id=eq." + URLEncoder.encode(chatId, "UTF-8")
                        + "&deleted_at=is.null&order=created_at.asc&limit=200";
                JSONObject result = request("GET", path, token, null, null);
                JSONArray rows = result.optJSONArray("_array");
                java.util.ArrayList<Message> messages = new java.util.ArrayList<>();
                if (rows != null) for (int i = 0; i < rows.length(); i++) messages.add(new Message(rows.getJSONObject(i)));
                MAIN.post(() -> callback.onSuccess(messages));
            } catch (Exception e) {
                FileLog.e(e);
                deliverError(callback, e.getMessage());
            }
        });
    }

    public static void loadGiftCatalog(Context context, Callback<JSONArray> callback) {
        IO.execute(() -> {
            try {
                String token = getAccessToken(context);
                JSONObject result = request("GET", "/rest/v1/gift_catalog?select=slug,title,asset_key,supply_limit,next_serial,price_stars&enabled=eq.true&order=title.asc",
                        token, null, null);
                JSONArray rows = result.optJSONArray("_array");
                MAIN.post(() -> callback.onSuccess(rows == null ? new JSONArray() : rows));
            } catch (Exception e) {
                FileLog.e(e);
                deliverError(callback, e.getMessage());
            }
        });
    }

    public static void claimGift(Context context, String slug, Callback<JSONObject> callback) {
        IO.execute(() -> {
            try {
                String token = getAccessToken(context);
                JSONObject result = request("POST", "/rest/v1/rpc/claim_monogram_gift", token,
                        new JSONObject().put("p_slug", slug), null);
                MAIN.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                FileLog.e(e);
                deliverError(callback, e.getMessage());
            }
        });
    }

    public static void registerDeviceToken(Context context, String fcmToken) {
        IO.execute(() -> {
            try {
                String token = getAccessToken(context);
                String userId = getCurrentUserId(context);
                if (token.isEmpty() || userId.isEmpty() || fcmToken == null || fcmToken.isEmpty()) return;
                JSONObject payload = new JSONObject().put("user_id", userId)
                        .put("fcm_token", fcmToken).put("platform", "android")
                        .put("updated_at", java.time.Instant.now().toString());
                request("POST", "/rest/v1/device_tokens?on_conflict=fcm_token", token,
                        payload, "resolution=merge-duplicates,return=minimal");
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public static void searchProfiles(Context context, String query, Callback<JSONArray> callback) {
        IO.execute(() -> {
            try {
                String token = getAccessToken(context);
                String safe = query == null ? "" : query.trim().replace("*", "").replace(",", "");
                String path = "/rest/v1/profiles?select=user_id,username,first_name,last_name,avatar_url,is_owner,premium_enabled"
                        + "&username=ilike.*" + URLEncoder.encode(safe, "UTF-8") + "*&limit=30";
                JSONObject result = request("GET", path, token, null, null);
                JSONArray rows = result.optJSONArray("_array");
                MAIN.post(() -> callback.onSuccess(rows == null ? new JSONArray() : rows));
            } catch (Exception e) {
                FileLog.e(e);
                deliverError(callback, e.getMessage());
            }
        });
    }
}
