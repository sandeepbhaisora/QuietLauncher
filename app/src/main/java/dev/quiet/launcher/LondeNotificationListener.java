package dev.quiet.launcher;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Opt-in Android notification access: no upload, persistence, or model prompt use.
 * Only reads currently ACTIVE notifications after a user request.
 */
public final class LondeNotificationListener extends NotificationListenerService {
    private static volatile WeakReference<LondeNotificationListener> current =
            new WeakReference<>(null);
    @Override public void onListenerConnected() {
        super.onListenerConnected();
        current = new WeakReference<>(this);
    }
    @Override public void onListenerDisconnected() {
        current = new WeakReference<>(null);
        super.onListenerDisconnected();
    }
    @Override public void onDestroy() {
        current = new WeakReference<>(null);
        super.onDestroy();
    }
    static boolean authorized(Context context) {
        return NotificationManager.getEnabledNotificationListenerPackages(context)
                .contains(context.getPackageName());
    }
    static boolean connected() { return current.get() != null; }

    static String readActive(boolean whatsappOnly) {
        LondeNotificationListener service=current.get();
        if (service==null)
            return "Notification access isn't connected yet. Enable it in Android Settings, then return to Quiet.";
        StatusBarNotification[] notifications;
        try { notifications=service.getActiveNotifications(); }
        catch (SecurityException ex) {
            return "Android didn't allow access to active notifications.";
        }
        if(notifications==null || notifications.length==0)
            return whatsappOnly ? "No visible WhatsApp notifications right now. Unread chats may still exist."
                    : "No visible notifications right now.";
        Arrays.sort(notifications,Comparator.comparingLong(StatusBarNotification::getPostTime).reversed());
        StringBuilder answer=new StringBuilder();
        int shown=0;
        for(StatusBarNotification item:notifications) {
            if(item==null || item.getNotification()==null)continue;
            Notification notice=item.getNotification();
            if((notice.flags & Notification.FLAG_GROUP_SUMMARY)!=0)continue;
            if(whatsappOnly && !isWhatsApp(item.getPackageName()))continue;
            if(!whatsappOnly && item.getPackageName().equals(service.getPackageName()))continue;
            Bundle extras=notice.extras;
            if(extras==null)continue;
            CharSequence title=extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence body=extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if(body==null || body.length()==0)body=extras.getCharSequence(Notification.EXTRA_TEXT);
            String sender=clean(title);
            String text=clean(body);
            if(sender.isEmpty() && text.isEmpty())continue;
            if(shown>0)answer.append("\n");
            answer.append(whatsappOnly?"WhatsApp":"Notification");
            if(!sender.isEmpty())answer.append(" · ").append(sender);
            if(!text.isEmpty())answer.append(": ").append(text);
            shown++;
            if(shown>=3)break;
        }
        if(shown==0)return whatsappOnly
                ? "No readable WhatsApp notifications are currently visible. This is not your full chat history."
                : "No readable notifications are currently visible.";
        return answer.toString();
    }
    private static boolean isWhatsApp(String name) {
        return "com.whatsapp".equals(name) || "com.whatsapp.w4b".equals(name);
    }
    private static String clean(CharSequence input) {
        if(input==null)return "";
        String s=input.toString().replaceAll("[\\r\\n\\t]+"," ").trim();
        return s.length()>180?s.substring(0,180)+"…":s;
    }
}
