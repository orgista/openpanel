package com.orgista.openpanel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Re-arms the standalone kiosk after a reboot.
 *
 * <p>Fire OS boots straight into the Amazon launcher, and OpenPanel's Fire kiosk is a
 * redirect kiosk driven by {@link HomeGestureAccessibilityService}. That service only
 * redirected from window-change events, so a launcher window that was already settled
 * and focused when the service bound produced no event and no redirect. Without this
 * receiver the tablet stayed on the Amazon launcher until something else happened to
 * change the foreground window.
 *
 * <p>The intent to be in kiosk is persisted in {@link KioskState}, so this only
 * re-asserts a state the operator already chose. It deliberately does nothing when the
 * operator has exited kiosk (the HOME alias is disabled).
 */
public final class KioskBootReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = "OpenPanel";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        // BOOT_COMPLETED only: this receiver is not direct-boot aware and KioskState
        // reads credential-encrypted SharedPreferences, which are unavailable before
        // the user unlocks.
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        if (!shouldReturnToOpenPanel(context)) {
            Log.i(LOG_TAG, "Boot completed; kiosk not armed, leaving the OEM launcher in place");
            return;
        }

        Intent launch = context.getPackageManager()
            .getLaunchIntentForPackage(context.getPackageName());
        if (launch == null) {
            Log.e(LOG_TAG, "Boot completed; could not resolve the OpenPanel launch intent");
            return;
        }
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        try {
            context.startActivity(launch);
            Log.i(LOG_TAG, "Boot completed; returned to the standalone OpenPanel kiosk");
        } catch (RuntimeException error) {
            // Android 10+ blocks background activity starts unless the app is exempt
            // (SYSTEM_ALERT_WINDOW is the exemption OpenPanel's kiosk already needs).
            // The accessibility service re-checks the foreground window on connect,
            // so the redirect still recovers when this path is refused.
            Log.w(LOG_TAG, "Boot activity start was refused; accessibility redirect will retry",
                error);
        }
    }

    /** Kiosk is armed only when standalone mode, the kiosk flag, and HOME all agree. */
    static boolean shouldReturnToOpenPanel(Context context) {
        return KioskState.MODE_STANDALONE.equals(KioskState.getMode(context))
            && KioskState.isEnabled(context)
            && DeviceAccess.isOpenPanelHomeEnabled(context);
    }
}
