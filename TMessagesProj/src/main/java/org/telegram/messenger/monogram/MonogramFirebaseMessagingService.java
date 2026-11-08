package org.telegram.messenger.monogram;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.telegram.ui.LaunchActivity;

public final class MonogramFirebaseMessagingService extends FirebaseMessagingService {
    private static final String CHANNEL_ID = "monogram_messages";

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        MonogramBackend.registerDeviceToken(this, token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);
        String title = message.getData().get("title");
        String body = message.getData().get("body");
        if (title == null || title.isEmpty()) title = "MONOGRAM";
        if (body == null || body.isEmpty()) body = "New message";

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "MONOGRAM messages", NotificationManager.IMPORTANCE_HIGH));
        }

        Intent launch = new Intent(this, LaunchActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 7001, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        NotificationCompat.Builder notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        manager.notify((int) (System.currentTimeMillis() & 0x7fffffff), notification.build());
    }
}
