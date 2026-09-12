package com.orgista.openpanel;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.ref.WeakReference;

/** Cancels distracting app notifications when the operator enables kiosk notification blocking. */
public final class OpenPanelNotificationListenerService extends NotificationListenerService {
    private static final String LOG_TAG = "OpenPanel";
    private static WeakReference<OpenPanelNotificationListenerService> connected =
        new WeakReference<>(null);

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = new WeakReference<>(this);
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
        // Re-resolve the default launcher per event so a changed default home's
        // notifications are never wrongly cancelled (nor wrongly preserved).
        if (NotificationBlockPolicy.shouldPreserve(
                notification.getPackageName(), getPackageName(),
                DeviceAccess.resolvedHomePackage(this))) {
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
        String homePackage = DeviceAccess.resolvedHomePackage(this);
        for (StatusBarNotification notification : active) {
            if (!NotificationBlockPolicy.shouldPreserve(
                    notification.getPackageName(), getPackageName(), homePackage)) {
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
}
