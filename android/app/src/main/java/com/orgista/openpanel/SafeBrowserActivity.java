package com.orgista.openpanel;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SafeBrowsingResponse;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.ByteArrayInputStream;

/**
 * Non-catalog browser used only when Android routes an escaped web link out of
 * OpenPanel. Navigation is limited to encrypted Kiddle/Kpedia pages.
 */
public final class SafeBrowserActivity extends AppCompatActivity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        LandscapeOrientationLock.enforce(this);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        hideSystemBars();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goBackOrPanel();
            }
        });
        setContentView(buildContent());
        loadIntent(getIntent());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadIntent(intent);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private LinearLayout buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(10, 10, 10));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(6), dp(8), dp(6));
        toolbar.setBackgroundColor(Color.rgb(24, 24, 24));

        Button back = toolbarButton("‹ Back");
        back.setOnClickListener(view -> goBackOrPanel());
        toolbar.addView(back);

        Button home = toolbarButton("Kiddle Home");
        home.setOnClickListener(view -> webView.loadUrl(SafeBrowserPolicy.HOME_URL));
        toolbar.addView(home);

        TextView title = new TextView(this);
        title.setText("Safe Browser · Kiddle only");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));

        Button close = toolbarButton("Close");
        close.setOnClickListener(view -> returnToPanel());
        toolbar.addView(close);
        root.addView(toolbar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.setLongClickable(false);
        webView.setDownloadListener((url, userAgent, disposition, mimeType, length) ->
            blockedMessage()
        );
        webView.getSettings().setJavaScriptEnabled(false);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        webView.getSettings().setSupportMultipleWindows(false);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setAllowFileAccessFromFileURLs(false);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        webView.getSettings().setDomStorageEnabled(false);
        webView.getSettings().setDatabaseEnabled(false);
        webView.getSettings().setGeolocationEnabled(false);
        webView.getSettings().setSaveFormData(false);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(true);
        webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.getSettings().setSafeBrowsingEnabled(true);
        }
        CookieManager.getInstance().setAcceptCookie(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        }
        webView.setWebChromeClient(new LockedChromeClient());
        webView.setWebViewClient(new LockedWebViewClient());
        root.addView(webView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1
        ));
        return root;
    }

    private Button toolbarButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setMinimumHeight(dp(44));
        return button;
    }

    private void loadIntent(Intent intent) {
        Uri requested = intent != null ? intent.getData() : null;
        String requestedUrl = requested != null ? requested.toString() : null;
        if (requestedUrl != null && !SafeBrowserPolicy.isAllowed(requestedUrl)) blockedMessage();
        webView.loadUrl(SafeBrowserPolicy.initialUrl(requestedUrl));
    }

    private void blockedMessage() {
        Toast.makeText(
            this,
            "That page is not on this device's approved list.",
            Toast.LENGTH_SHORT
        ).show();
    }

    private void goBackOrPanel() {
        if (webView.canGoBack()) webView.goBack();
        else returnToPanel();
    }

    private void returnToPanel() {
        Intent panel = new Intent(this, MainActivity.class);
        panel.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(panel);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );
    }

    private final class LockedWebViewClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(
            WebView view,
            WebResourceRequest request
        ) {
            Uri uri = request != null ? request.getUrl() : null;
            if (SafeBrowserPolicy.isAllowed(uri != null ? uri.toString() : null)) return null;
            return emptyResponse();
        }

        @SuppressWarnings("deprecation")
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            if (SafeBrowserPolicy.isAllowed(url)) return null;
            return emptyResponse();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return blockUnlessAllowed(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return blockUnlessAllowed(Uri.parse(url));
        }

        private boolean blockUnlessAllowed(Uri uri) {
            if (SafeBrowserPolicy.isAllowed(uri != null ? uri.toString() : null)) return false;
            blockedMessage();
            return true;
        }

        private WebResourceResponse emptyResponse() {
            return new WebResourceResponse(
                "text/plain",
                "UTF-8",
                new ByteArrayInputStream(new byte[0])
            );
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            blockedMessage();
        }

        @RequiresApi(Build.VERSION_CODES.O_MR1)
        @Override
        public void onSafeBrowsingHit(
            WebView view,
            WebResourceRequest request,
            int threatType,
            @NonNull SafeBrowsingResponse callback
        ) {
            callback.backToSafety(true);
            blockedMessage();
        }
    }

    private static final class LockedChromeClient extends WebChromeClient {
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }

        @Override
        public boolean onCreateWindow(
            WebView view,
            boolean isDialog,
            boolean isUserGesture,
            android.os.Message resultMsg
        ) {
            return false;
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(
            String origin,
            GeolocationPermissions.Callback callback
        ) {
            callback.invoke(origin, false, false);
        }
    }
}
