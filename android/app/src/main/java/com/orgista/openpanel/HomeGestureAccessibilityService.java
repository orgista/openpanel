package com.orgista.openpanel;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Opt-in escape gesture for managed tablets whose DPC disables Android's Home
 * gesture while another allow-listed app is immersive. The service cannot read
 * screen content; it only owns a thin bottom-edge accessibility overlay.
 */
public final class HomeGestureAccessibilityService extends AccessibilityService {
    private static final String LOG_TAG = "OpenPanel";
    private static final String PRODUCTION_PACKAGE = "com.orgista.openpanel";
    private static final int HANDLE_HEIGHT_DP = 32;
    private static final int MINIMUM_SWIPE_DP = 40;
    private static final int MAXIMUM_HORIZONTAL_DRIFT_DP = 96;

    private WindowManager windowManager;
    private View homeHandle;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        addHomeHandle();
        Log.i(LOG_TAG, "Bottom-swipe Home service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        String windowPackage = packageName == null ? null : packageName.toString();
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
        removeHomeHandle();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        removeHomeHandle();
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
