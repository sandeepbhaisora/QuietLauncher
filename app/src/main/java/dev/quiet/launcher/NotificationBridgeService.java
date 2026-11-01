package dev.quiet.launcher;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.Notification;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Only reads notifications after the user explicitly enables Notification access. */
public final class NotificationBridgeService extends NotificationListenerService {
    private static volatile NotificationBridgeService active;
    public static NotificationBridgeService current() { return active; }

    @Override public void onListenerConnected() { super.onListenerConnected(); active = this; }
    @Override public void onListenerDisconnected() { active = null; super.onListenerDisconnected(); }
    @Override public void onDestroy() { active = null; super.onDestroy(); }

    public List<StatusBarNotification> recent() {
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all == null) return new ArrayList<>();
            Arrays.sort(all, Comparator.comparingLong(StatusBarNotification::getPostTime).reversed());
            List<StatusBarNotification> out = new ArrayList<>();
            for (StatusBarNotification item : all) {
                if (item.isOngoing()) continue;
                if (getPackageName().equals(item.getPackageName())) continue;
                Bundle extras = item.getNotification().extras;
                if (extras == null) continue;
                CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
                if (title == null && text == null) continue;
                out.add(item);
                if (out.size() == 40) break;
            }
            return out;
        } catch (Exception ex) { return new ArrayList<>(); }
    }
}
