package com.orgista.openpanel;

import android.app.Notification;
import android.content.Intent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.ref.WeakReference;

/** Cancels distracting app notifications when the operator enables kiosk notification blocking. */
public final class OpenPanelNotificationListenerService extends NotificationListenerService {
    private static final String LOG_TAG = "OpenPanel";
    private static WeakReference<OpenPanelNotificationListenerService> connected =
        new WeakReference<>(null);

    private String resolvedHomePackage;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = new WeakReference<>(this);
        resolvedHomePackage = resolveHomePackage();
        cancelManagedNotifications();
        Log.i(LOG_TAG, "Notification blocker connected");
    }

    @Override
    public void onListenerDisconnected() {
        connected.clear();
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        if (notification == null || !NotificationBlockPolicy.isEnabled(this)) return;
        if (NotificationBlockPolicy.shouldPreserve(
                notification.getPackageName(), getPackageName(), resolvedHomePackage)) {
            return;
        }
        cancelSafely(notification);
    }

    static void applyNow() {
        OpenPanelNotificationListenerService service = connected.get();
        if (service != null) service.cancelManagedNotifications();
    }

    private void cancelManagedNotifications() {
        if (!NotificationBlockPolicy.isEnabled(this)) return;
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "Could not read active notifications", error);
            return;
        }
        if (active == null) return;
        for (StatusBarNotification notification : active) {
            if (!NotificationBlockPolicy.shouldPreserve(
                    notification.getPackageName(), getPackageName(), resolvedHomePackage)) {
                cancelSafely(notification);
            }
        }
    }

    private void cancelSafely(StatusBarNotification notification) {
        Notification posted = notification.getNotification();
        // Android may reject cancellation of certain immutable platform
        // notifications. Keep going without affecting the underlying service.
        try {
            cancelNotification(notification.getKey());
            Log.i(LOG_TAG, "Notification blocked package=" + notification.getPackageName()
                + " ongoing=" + (posted != null && (posted.flags & Notification.FLAG_ONGOING_EVENT) != 0));
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "Could not block notification from " + notification.getPackageName(), error);
        }
    }

    private String resolveHomePackage() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo resolved = getPackageManager().resolveActivity(home, 0);
        return resolved != null && resolved.activityInfo != null
            ? resolved.activityInfo.packageName : null;
    }
}
