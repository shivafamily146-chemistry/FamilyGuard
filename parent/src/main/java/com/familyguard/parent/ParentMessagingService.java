package com.familyguard.parent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class ParentMessagingService extends FirebaseMessagingService {
    private static final String CHANNEL = "familyguard_remote_alerts";

    @Override public void onNewToken(String token) {
        if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
            String uid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("parentProfiles").document(uid)
                .set(java.util.Collections.singletonMap("fcmToken", token),
                    com.google.firebase.firestore.SetOptions.merge());
        }
    }

    @Override public void onMessageReceived(RemoteMessage message) {
        String title = message.getData().get("title");
        String body = message.getData().get("body");
        if (message.getNotification() != null) {
            if (title == null) title = message.getNotification().getTitle();
            if (body == null) body = message.getNotification().getBody();
        }
        if (title == null) title = "FamilyGuard alert";
        if (body == null) body = "A Child device has an update.";
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "FamilyGuard remote alerts", NotificationManager.IMPORTANCE_HIGH));
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(body).setAutoCancel(true).build();
        manager.notify((int)(System.currentTimeMillis() & 0x7fffffff), notification);
    }
}
