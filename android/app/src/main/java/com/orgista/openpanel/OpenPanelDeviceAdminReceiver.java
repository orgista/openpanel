package com.orgista.openpanel;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;

/**
 * Device-admin receiver for OpenPanel's standalone (non-ArborXR) kiosk mode.
 *
 * Becoming a device admin lets OpenPanel lock the screen and (when the device is
 * also provisioned as Device Owner via adb/QR) silently enter lock-task mode and
 * pin the allowed-app set. Without Device Owner, OpenPanel falls back to Android
 * screen pinning, which is weaker but needs no provisioning.
 */
public class OpenPanelDeviceAdminReceiver extends DeviceAdminReceiver {
    public static ComponentName getComponentName(Context context) {
        return new ComponentName(context.getApplicationContext(), OpenPanelDeviceAdminReceiver.class);
    }
}
