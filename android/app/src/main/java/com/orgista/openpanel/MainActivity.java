package com.orgista.openpanel;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Bundle;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SystemBridgePlugin.class);
        super.onCreate(savedInstanceState);
        hideSystemBars();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
            pinKioskIfUnlocked();
        }
    }

    // Screen pinning needs no Device Owner, so this closes the gesture-nav
    // "swipe up and hold" app dock and the Overview/Recents screen on every
    // device, companion or standalone. Re-engages whenever this activity
    // regains window focus (e.g. backing out of a launched app);
    // SystemBridgePlugin unpins first whenever it deliberately starts another
    // activity (launching an app, opening a settings screen).
    private void pinKioskIfUnlocked() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null || am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) return;
        try {
            startLockTask();
        } catch (Exception ignored) {
            // Retried on the next focus-gain if the platform briefly refused it.
        }
    }

    private void hideSystemBars() {
        // Decor keeps fitting system windows (the default): opting out of it
        // disables adjustResize, so the WebView would no longer shrink when the
        // soft keyboard opens and fixed-position UI would hide behind the IME.
        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        // Bars stay hidden; a swipe from the edge reveals them briefly.
        controller.setSystemBarsBehavior(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
}
