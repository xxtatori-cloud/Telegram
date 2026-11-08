package org.telegram.messenger.monogram;

import android.content.Context;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;

import org.json.JSONObject;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;

import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

/** Supabase Realtime subscription for a single MONOGRAM chat. */
public final class MonogramRealtimeClient extends WebSocketAdapter implements AutoCloseable {
    public interface Listener {
        void onMessage(MonogramBackend.Message message);
        void onError(String error);
    }

    private final String chatId;
    private final String accessToken;
    private final Listener listener;
    private WebSocket socket;

    public MonogramRealtimeClient(Context context, String chatId, Listener listener) {
        this.chatId = chatId;
        this.listener = listener;
        this.accessToken = MonogramBackend.getAccessToken(context);
    }

    public void connect() {
        try {
            if (!MonogramBackend.isConfigured()) {
                throw new IllegalStateException("Supabase is not configured for this MONOGRAM build.");
            }
            if (accessToken == null || accessToken.isEmpty()) {
                throw new IllegalStateException("Sign in to MONOGRAM before subscribing to messages.");
            }
            String base = BuildConfig.MONOGRAM_SUPABASE_URL.replaceFirst("^https:", "wss:")
                    .replaceFirst("^http:", "ws:").replaceAll("/+$", "");
            String url = base + "/realtime/v1/websocket?apikey="
                    + URLEncoder.encode(BuildConfig.MONOGRAM_SUPABASE_ANON_KEY, "UTF-8")
                    + "&vsn=1.0.0";
            socket = new WebSocketFactory().createSocket(url, 15000);
            socket.setPingInterval(30000);
            socket.addListener(this);
            socket.connectAsynchronously();
        } catch (Exception e) {
            FileLog.e(e);
            if (listener != null) listener.onError(e.getMessage());
        }
    }

    @Override
    public void onConnected(WebSocket websocket, Map<String, List<String>> headers) {
        try {
            JSONObject filter = new JSONObject()
                    .put("event", "INSERT")
                    .put("schema", "public")
                    .put("table", "messages")
                    .put("filter", "chat_id=eq." + chatId);
            JSONObject postgres = new JSONObject().put("postgres_changes", new org.json.JSONArray().put(filter));
            JSONObject config = new JSONObject()
                    .put("broadcast", new JSONObject().put("ack", false).put("self", false))
                    .put("presence", new JSONObject().put("key", ""))
                    .put("postgres_changes", postgres.optJSONArray("postgres_changes"));
            JSONObject payload = new JSONObject().put("config", config).put("access_token", accessToken);
            JSONObject join = new JSONObject()
                    .put("topic", "realtime:chat:" + chatId)
                    .put("event", "phx_join")
                    .put("payload", payload)
                    .put("ref", "1");
            websocket.sendText(join.toString());
        } catch (Exception e) {
            FileLog.e(e);
            if (listener != null) listener.onError(e.getMessage());
        }
    }

    @Override
    public void onTextMessage(WebSocket websocket, String text) {
        try {
            JSONObject envelope = new JSONObject(text);
            String event = envelope.optString("event", "");
            if (!"postgres_changes".equals(event)) return;
            JSONObject payload = envelope.optJSONObject("payload");
            if (payload == null) return;
            JSONObject data = payload.optJSONObject("data");
            if (data == null) return;
            JSONObject record = data.optJSONObject("record");
            if (record == null || !chatId.equals(record.optString("chat_id", ""))) return;
            if (listener != null) listener.onMessage(new MonogramBackend.Message(record));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onError(WebSocket websocket, WebSocketException cause) {
        FileLog.e(cause);
        if (listener != null) listener.onError(cause.getMessage());
    }

    @Override
    public void onDisconnected(WebSocket websocket,
                               com.neovisionaries.ws.client.WebSocketFrame serverCloseFrame,
                               com.neovisionaries.ws.client.WebSocketFrame clientCloseFrame,
                               boolean closedByServer) {
        if (listener != null) listener.onError("MONOGRAM realtime connection closed.");
    }

    @Override
    public void close() {
        if (socket != null) {
            socket.disconnect();
            socket = null;
        }
    }
}
