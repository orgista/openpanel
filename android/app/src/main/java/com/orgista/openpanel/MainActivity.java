package com.orgista.openpanel;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static final String LOG_TAG = "OpenPanel";
    private static final String DISPATCH_REMOTE_BACK_SCRIPT =
        "(function(){"
            + "var scopes=Array.prototype.slice.call(document.querySelectorAll('[data-dpad-scope]'));"
            + "var target=document.body;var highest=-2147483648;"
            + "scopes.forEach(function(scope){"
                + "var rect=scope.getBoundingClientRect();var style=getComputedStyle(scope);"
                + "if(rect.width<=0||rect.height<=0||style.display==='none'||style.visibility==='hidden'||scope.getAttribute('aria-hidden')==='true')return;"
                + "var z=parseInt(style.zIndex,10);if(isNaN(z))z=0;"
                + "if(z>=highest){highest=z;target=scope;}"
            + "});"
            + "target.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true,cancelable:true}));"
        + "})();";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Theme.SplashScreen on pre-Android 12 devices needs the compat
        // handoff before BridgeActivity creates its WebView. Without this the
        // TCL launch window remains solid black throughout Capacitor startup.
        SplashScreen.installSplashScreen(this);
        registerPlugin(SystemBridgePlugin.class);
        registerPlugin(LibraryBridgePlugin.class);
        super.onCreate(savedInstanceState);
        logLifecycle("created");
        installRemoteBackHandler();
        configureWebViewTextInput();
        LandscapeOrientationLock.enforce(this);
        hideSystemBars();
        KioskVolumePolicy.enforceTarget(this);
    }

    @Override
    public void onStart() {
        super.onStart();
        logLifecycle("started");
    }

    @Override
    public void onResume() {
        super.onResume();
        LandscapeOrientationLock.enforce(this);
        hideSystemBars();
        KioskVolumePolicy.enforceTarget(this);
        logLifecycle("resumed");
    }

    @Override
    public void onPause() {
        logLifecycle("paused");
        super.onPause();
    }

    @Override
    public void onStop() {
        logLifecycle("stopped");
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.i(LOG_TAG, "Activity received new intent action="
            + (intent != null ? intent.getAction() : "null"));
    }

    private void logLifecycle(String state) {
        ActivityManager activityManager =
            (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        int lockState = activityManager != null
            ? activityManager.getLockTaskModeState()
            : ActivityManager.LOCK_TASK_MODE_NONE;
        Log.i(LOG_TAG, "Activity " + state + " lockTask=" + lockState);
    }

    private void installRemoteBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView webView = getBridge().getWebView();
                if (webView == null) return;
                Log.i(LOG_TAG, "Remote Back dispatched to the active OpenPanel screen");
                webView.evaluateJavascript(DISPATCH_REMOTE_BACK_SCRIPT, null);
            }
        });
    }

    private void configureWebViewTextInput() {
        WebView webView = getBridge().getWebView();
        if (webView == null) return;

        // Android 14 enables stylus handwriting automatically for WebView
        // editors. On the Lenovo TB132FU its pen digitizer can trigger a large
        // handwriting/selection popup over Gboard, so OpenPanel keeps its
        // ordinary keyboard and voice-input paths and opts out of that overlay.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            webView.setAutoHandwritingEnabled(false);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            LandscapeOrientationLock.enforce(this);
            hideSystemBars();
            KioskVolumePolicy.enforceTarget(this);
            if (KioskState.shouldAutoPin(this)) pinKioskIfUnlocked();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KioskVolumePolicy.isVolumeMutationKey(event.getKeyCode())
                && KioskVolumePolicy.shouldLock(this)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                KioskVolumePolicy.enforceTarget(this);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // In explicitly enabled standalone mode, screen pinning closes the
    // gesture-nav app dock and Overview/Recents. Companion mode never starts
    // its own lock task because ArborXR owns device policy there. Re-engages
    // whenever this activity regains focus (e.g. backing out of a launched app);
    // SystemBridgePlugin unpins first whenever it deliberately starts another
    // activity (launching an app, opening a settings screen).
    private void pinKioskIfUnlocked() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null || am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) return;
        // As Device Owner, allowlist ourselves + hide the status bar first so
        // startLockTask() enters silent LOCKED mode. Without this it falls back
        // to user-confirmed screen pinning and Android shows the "App is pinned"
        // dialog every time. Not DO -> plain pinning (unchanged fallback).
        KioskLock.applyDeviceOwnerLockdown(this, null);
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

        // Fire OS 8 keeps a black gesture-navigation inset when only the
        // WindowInsets API is used. The legacy immersive-layout flags are
        // still honored there and let the WebView fill the full 1024x600
        // display while preserving transient edge reveals.
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
    }
}
