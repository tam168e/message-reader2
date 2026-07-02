package com.farsi.messagereader;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class FarsiNotificationListener extends NotificationListenerService {

    private static final String TAG = "FarsiNotifListener";

    // Supported messaging apps
    private static final Set<String> MESSAGING_APPS = new HashSet<>(Arrays.asList(
        "com.whatsapp",                    // WhatsApp
        "org.telegram.messenger",          // Telegram
        "org.telegram.messenger.web",      // Telegram X
        "com.instagram.android",           // Instagram DMs
        "com.twitter.android",             // Twitter/X DMs
        "com.facebook.orca",               // Messenger
        "com.google.android.apps.messaging",// Google Messages
        "com.samsung.android.messaging",   // Samsung Messages
        "com.discord",                     // Discord
        "ir.eitaa.messenger",              // Eitaa (Iranian messenger)
        "com.gap.android",                 // Gap (Iranian messenger)
        "com.bale.messenger"               // Bale (Iranian messenger)
    ));

    // Prevent duplicate notifications within 3 seconds
    private long lastNotifTime = 0;
    private static final long DEBOUNCE_MS = 3000;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        String packageName = sbn.getPackageName();

        // Only handle known messaging apps
        if (!MESSAGING_APPS.contains(packageName)) return;

        // Debounce rapid notifications
        long now = System.currentTimeMillis();
        if (now - lastNotifTime < DEBOUNCE_MS) return;
        lastNotifTime = now;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        if (extras == null) return;

        // Extract sender and message text
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);

        if (text == null || text.toString().trim().isEmpty()) return;

        String sender = (title != null) ? title.toString() : getAppName(packageName);
        String message = text.toString().trim();

        Log.d(TAG, "New message from " + sender + ": " + message);

        // Forward to handler service
        Intent serviceIntent = new Intent(this, MessageHandlerService.class);
        serviceIntent.setAction(MessageHandlerService.ACTION_NEW_MESSAGE);
        serviceIntent.putExtra("sender", sender);
        serviceIntent.putExtra("message", message);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    private String getAppName(String packageName) {
        switch (packageName) {
            case "com.whatsapp": return "واتساپ";
            case "org.telegram.messenger":
            case "org.telegram.messenger.web": return "تلگرام";
            case "ir.eitaa.messenger": return "ایتا";
            case "com.gap.android": return "گپ";
            case "com.bale.messenger": return "بله";
            case "com.instagram.android": return "اینستاگرام";
            case "com.facebook.orca": return "مسنجر";
            case "com.discord": return "دیسکورد";
            default: return "پیام‌رسان";
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Not needed
    }
}
