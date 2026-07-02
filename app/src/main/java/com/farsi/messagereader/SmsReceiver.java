package com.farsi.messagereader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import androidx.core.content.ContextCompat;

public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;

        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) return;

        StringBuilder fullBody = new StringBuilder();
        String sender = messages[0].getDisplayOriginatingAddress();

        for (SmsMessage sms : messages) {
            fullBody.append(sms.getMessageBody());
        }

        // Start the handler service
        Intent serviceIntent = new Intent(context, MessageHandlerService.class);
        serviceIntent.setAction(MessageHandlerService.ACTION_NEW_MESSAGE);
        serviceIntent.putExtra("sender", formatSender(sender));
        serviceIntent.putExtra("message", fullBody.toString());
        ContextCompat.startForegroundService(context, serviceIntent);
    }

    private String formatSender(String sender) {
        if (sender == null) return "ناشناس";
        // Clean up phone number for Persian reading
        return sender.replaceAll("\\+98", "0")
                     .replaceAll("[^0-9a-zA-Z\u0600-\u06FF ]", " ");
    }
}
