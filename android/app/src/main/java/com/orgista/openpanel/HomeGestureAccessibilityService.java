package com.orgista.openpanel;

import android.annotation.SuppressLint;
import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Opt-in escape gesture for managed tablets whose DPC disables Android's Home
 * gesture while another allow-listed app is immersive. The service cannot read
 * screen text; it owns thin edge overlays and checks active window identity.
 */
public final class HomeGestureAccessibilityService extends AccessibilityService {
    private static final String LOG_TAG = "OpenPanel";
    private static final String PRODUCTION_PACKAGE = "com.orgista.openpanel";
    private static final int HANDLE_HEIGHT_DP = 32;
    private static final int SHADE_GUARD_HEIGHT_DP = 32;
    private static final int MINIMUM_SWIPE_DP = 40;
    private static final int MAXIMUM_HORIZONTAL_DRIFT_DP = 96;
    private static final long FIRE_LAUNCHER_REDIRECT_DEBOUNCE_MS = 500;
    private static final long SHADE_COLLAPSE_DEBOUNCE_MS = 400;
    private static final String HOME_ALIAS_CLASS =
        "com.orgista.openpanel.OpenPanelHomeActivity";

    private WindowManager windowManager;
    private View homeHandle;
    private View shadeGuard;
    private long lastFireLauncherRedirectMs;
    private long lastShadeCollapseMs;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        LandscapeOrientationLock.enforce(this);
        addHomeHandle();
        addShadeGuard();
        syncShadeGuardVisibility(getPackageName());
        Log.i(LOG_TAG, "Home gesture and system-UI protection service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        LandscapeOrientationLock.enforce(this);
        if (event == null
                || (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && event.getEventType() != AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        String windowPackage = packageName == null ? null : packageName.toString();
        boolean homeEnabled = isOpenPanelHomeEnabled();
        String managementMode = KioskState.getMode(this);
        syncShadeGuardVisibility(windowPackage);

        String windowClass = event.getClassName() == null
            ? null : event.getClassName().toString();
        String windowTitle = notificationShadeTitle(event.getWindowId());
        if (SystemUiProtection.shouldCollapse(
                windowPackage, windowClass, windowTitle, managementMode, homeEnabled)) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastShadeCollapseMs >= SHADE_COLLAPSE_DEBOUNCE_MS) {
                lastShadeCollapseMs = now;
                Log.i(LOG_TAG, "Notification shade intercepted title=" + windowTitle
                    + " class=" + windowClass);
                collapseNotificationShade();
            }
            return;
        }
        if (FireLauncherRedirect.shouldRedirect(
                windowPackage,
                managementMode,
                homeEnabled)) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastFireLauncherRedirectMs >= FIRE_LAUNCHER_REDIRECT_DEBOUNCE_MS) {
                lastFireLauncherRedirectMs = now;
                setHandleVisible(false, windowPackage);
                Log.i(LOG_TAG, "Fire Launcher intercepted; returning to standalone OpenPanel");
                returnToOpenPanel();
            }
            return;
        }
        Boolean visible = HomeGestureVisibility.forWindowPackage(
            windowPackage
        );
        if (visible != null) setHandleVisible(visible, windowPackage);
    }

    @Override
    public void onInterrupt() {
        // No ongoing accessibility feedback to interrupt.
    }

    @Override
    public boolean onUnbind(Intent intent) {
        removeOverlays();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        removeOverlays();
        super.onDestroy();
    }

    private void addHomeHandle() {
        if (homeHandle != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            Log.e(LOG_TAG, "Bottom-swipe Home overlay could not access WindowManager");
            return;
        }

        homeHandle = new HomeHandleView();
        homeHandle.setVisibility(View.INVISIBLE);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(HANDLE_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.setTitle("OpenPanel Home gesture");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        try {
            windowManager.addView(homeHandle, params);
        } catch (RuntimeException error) {
            Log.e(LOG_TAG, "Bottom-swipe Home overlay could not be added", error);
            homeHandle = null;
        }
    }

    private void setHandleVisible(boolean visible, String windowPackage) {
        if (homeHandle == null) return;
        int nextVisibility = visible ? View.VISIBLE : View.INVISIBLE;
        if (homeHandle.getVisibility() != nextVisibility) {
            homeHandle.setVisibility(nextVisibility);
            Log.i(LOG_TAG, "Bottom-swipe Home handle "
                + (visible ? "shown" : "hidden")
                + " for package=" + windowPackage);
        }
    }

    private void addShadeGuard() {
        if (shadeGuard != null) return;
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        if (windowManager == null) {
            Log.e(LOG_TAG, "Top-edge shade guard could not access WindowManager");
            return;
        }

        shadeGuard = new View(this) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                return true;
            }
        };
        shadeGuard.setBackgroundColor(Color.TRANSPARENT);
        shadeGuard.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        shadeGuard.setVisibility(View.INVISIBLE);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(SHADE_GUARD_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.setTitle("OpenPanel top edge guard");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            windowManager.addView(shadeGuard, params);
        } catch (RuntimeException error) {
            Log.e(LOG_TAG, "Top-edge shade guard could not be added", error);
            shadeGuard = null;
        }
    }

    private void syncShadeGuardVisibility(String windowPackage) {
        if (shadeGuard == null) return;
        boolean visible = SystemUiProtection.isEnabled(
            KioskState.getMode(this), isOpenPanelHomeEnabled())
            && !getPackageName().equals(windowPackage)
            && !PRODUCTION_PACKAGE.equals(windowPackage);
        shadeGuard.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    private String notificationShadeTitle(int eventWindowId) {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return null;
            String fallback = null;
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                CharSequence title = window.getTitle();
                if (title == null) continue;
                String value = title.toString();
                if (window.getId() == eventWindowId) return value;
                if (value.toLowerCase(java.util.Locale.US).contains("notification")) {
                    fallback = value;
                }
            }
            return fallback;
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "Could not inspect system-UI window identity", error);
            return null;
        }
    }

    @SuppressLint("WrongConstant")
    private void collapseNotificationShade() {
        boolean collapsed = false;
        try {
            Object statusBar = getSystemService("statusbar");
            if (statusBar != null) {
                Method collapsePanels = statusBar.getClass().getMethod("collapsePanels");
                collapsePanels.setAccessible(true);
                collapsePanels.invoke(statusBar);
                collapsed = true;
            }
        } catch (Exception error) {
            Log.i(LOG_TAG, "Fire OS denied direct shade collapse; using accessibility Back");
        }
        if (!collapsed && !performGlobalAction(GLOBAL_ACTION_BACK)) {
            Log.w(LOG_TAG, "Notification shade could not be collapsed");
        }
    }

    private void removeHomeHandle() {
        if (windowManager == null || homeHandle == null) return;
        try {
            windowManager.removeView(homeHandle);
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "Bottom-swipe Home overlay was already removed", error);
        } finally {
            homeHandle = null;
        }
    }

    private void removeShadeGuard() {
        if (windowManager == null || shadeGuard == null) return;
        try {
            windowManager.removeView(shadeGuard);
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "Top-edge shade guard was already removed", error);
        } finally {
            shadeGuard = null;
        }
    }

    private void removeOverlays() {
        removeHomeHandle();
        removeShadeGuard();
    }

    private void returnToOpenPanel() {
        String targetPackage = getPackageName();
        if (targetPackage.endsWith(".debug") && isInstalled(PRODUCTION_PACKAGE)) {
            targetPackage = PRODUCTION_PACKAGE;
        }
        Intent intent = getPackageManager().getLaunchIntentForPackage(targetPackage);
        if (intent == null) {
            Log.e(LOG_TAG, "Bottom-swipe Home could not resolve " + targetPackage);
            return;
        }
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        try {
            startActivity(intent);
            Log.i(LOG_TAG, "Bottom-swipe Home requested launcher package=" + targetPackage);
        } catch (RuntimeException error) {
            Log.e(LOG_TAG, "Bottom-swipe Home could not open the launcher", error);
        }
    }

    private boolean isInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private boolean isOpenPanelHomeEnabled() {
        ComponentName homeAlias = new ComponentName(getPackageName(), HOME_ALIAS_CLASS);
        int state = getPackageManager().getComponentEnabledSetting(homeAlias);
        return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class HomeHandleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF pill = new RectF();
        private final HomeGestureDetector detector = new HomeGestureDetector(
            dp(MINIMUM_SWIPE_DP),
            dp(MAXIMUM_HORIZONTAL_DRIFT_DP)
        );

        HomeHandleView() {
            super(HomeGestureAccessibilityService.this);
            paint.setColor(Color.WHITE);
            setContentDescription(getString(R.string.home_gesture_handle_description));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float pillWidth = dp(72);
            float pillHeight = dp(4);
            float left = (getWidth() - pillWidth) / 2f;
            // Match Android's gesture handle: compact and close to the edge.
            float top = dp(22);
            pill.set(left, top, left + pillWidth, top + pillHeight);
            paint.setAlpha(230);
            canvas.drawRoundRect(pill, pillHeight / 2f, pillHeight / 2f, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    detector.onDown(event.getRawX(), event.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                    if (detector.onUp(event.getRawX(), event.getRawY())) {
                        performClick();
                        returnToOpenPanel();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    detector.onCancel();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
