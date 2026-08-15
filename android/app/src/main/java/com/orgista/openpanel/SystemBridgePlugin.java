package com.orgista.openpanel;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.UiModeManager;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.Html;
import android.util.Base64;
import android.util.Log;
import android.view.InputDevice;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import androidx.annotation.RequiresApi;
import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@CapacitorPlugin(
    name = "SystemBridge",
    permissions = {
        @Permission(alias = "location", strings = {Manifest.permission.ACCESS_FINE_LOCATION}),
        @Permission(alias = "wifi", strings = {Manifest.permission.NEARBY_WIFI_DEVICES}),
        @Permission(alias = "bluetooth", strings = {
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        })
    }
)
public class SystemBridgePlugin extends Plugin {

    // ArborXR's MDM client is the Device Owner on managed devices.
    private static final String ARBORXR_DPC = NotificationBlockPolicy.ARBORXR_DPC_PACKAGE;
    private static final String LOG_TAG = "OpenPanel";
    private static final String HOME_ALIAS_CLASS = DeviceAccess.HOME_ALIAS_CLASS;
    // Prefs contract shared with NotificationBlockPolicy (single source of truth).
    private static final String DEVICE_HEALTH_PREFS = NotificationBlockPolicy.PREFS;
    private static final String PREF_MANAGE_NOTIFICATIONS = NotificationBlockPolicy.PREF_ENABLED;
    private static final String PREF_HIDDEN_PACKAGES = "hidden_packages";
    private static final String PREF_NOTIFICATION_PACKAGES = "notification_packages";
    // Native mirror of the web layer's admin PIN verifier; see requireKioskAdmin().
    private static final String PREF_KIOSK_ADMIN_VERIFIER = "kiosk_admin_verifier";
    private static final OkHttpClient YOUTUBE_HTTP_CLIENT = new OkHttpClient.Builder()
        .dns(Ipv4FirstDns.INSTANCE)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build();

    private BroadcastReceiver btScanReceiver;
    private BroadcastReceiver btStateReceiver;
    private BroadcastReceiver bondReceiver;
    // The kept-alive pairBluetooth call currently awaiting a bond result, so a
    // superseding pair request or teardown can settle it instead of leaking it.
    private PluginCall pendingPairCall;
    private BroadcastReceiver batteryReceiver;
    // Encoded launcher icons keyed by "package@versionCode" so getInstalledApps
    // never re-rasterizes an unchanged icon.
    private final Map<String, String> iconCache = new ConcurrentHashMap<>();
    private final List<JSObject> btScanResults = new ArrayList<>();
    // Guards the in-flight scan call + its watchdog so a scan settles exactly
    // once, even if the timeout, DISCOVERY_FINISHED, and adapter-off events race.
    private final Object btScanLock = new Object();
    private PluginCall btScanCall;
    private Runnable btScanTimeout;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long BT_SCAN_TIMEOUT_MS = 20_000;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    // Access-ordered so the least-recently-used continuation cursor is evicted
    // when the map is full, instead of clearing every cursor (which would break
    // an open channel browser mid-scroll).
    private final Map<String, YouTubeCatalogCursor> youtubeCatalogCursors =
        new LinkedHashMap<>(16, 0.75f, true);

    @PluginMethod
    public void writeDiagnostic(PluginCall call) {
        String level = call.getString("level", "info");
        String message = call.getString("message", "OpenPanel diagnostic");
        if (message.length() > 2_000) message = message.substring(0, 2_000);
        if ("error".equals(level)) {
            Log.e(LOG_TAG, message);
        } else if ("warning".equals(level)) {
            Log.w(LOG_TAG, message);
        } else {
            Log.i(LOG_TAG, message);
        }
        call.resolve();
    }

    @PluginMethod
    public void hideKeyboard(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            View focused = getActivity().getCurrentFocus();
            View target = focused != null ? focused : getBridge().getWebView();
            InputMethodManager keyboard = (InputMethodManager) getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (focused != null) focused.clearFocus();
            if (keyboard != null && target != null && target.getWindowToken() != null) {
                keyboard.hideSoftInputFromWindow(target.getWindowToken(), 0);
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void showKeyboard(PluginCall call) {
        // WebView does not raise the IME for programmatic focus() (only a real
        // touch does), so admin fields that auto-focus never showed a keyboard.
        // Force it for the currently focused field.
        getActivity().runOnUiThread(() -> {
            View target = getBridge().getWebView();
            InputMethodManager keyboard = (InputMethodManager) getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null && target != null) {
                target.requestFocus();
                keyboard.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void setKeepScreenOn(PluginCall call) {
        boolean enabled = call.getBoolean("enabled", false);
        getActivity().runOnUiThread(() -> {
            if (enabled) {
                getActivity().getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getActivity().getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void setLegacyTvCompatRendering(PluginCall call) {
        boolean enabled = call.getBoolean("enabled", false);
        Activity activity = getActivity();
        WebView webView = getBridge() != null ? getBridge().getWebView() : null;
        boolean legacyTelevision = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
            && getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);

        if (activity == null || webView == null || !legacyTelevision) {
            JSObject result = new JSObject();
            result.put("applied", false);
            result.put("enabled", enabled);
            call.resolve(result);
            return;
        }

        activity.runOnUiThread(() -> {
            webView.setLayerType(
                enabled ? View.LAYER_TYPE_SOFTWARE : View.LAYER_TYPE_HARDWARE,
                null
            );
            Log.i(LOG_TAG, "Legacy TV compatibility rendering " + (enabled ? "enabled" : "disabled"));
            JSObject result = new JSObject();
            result.put("applied", true);
            result.put("enabled", enabled);
            call.resolve(result);
        });
    }

    // ---------- Google TV input + API-key-free YouTube channel verification ----------

    @PluginMethod
    public void getDeviceProfile(PluginCall call) {
        PackageManager pm = getContext().getPackageManager();
        Configuration configuration = getContext().getResources().getConfiguration();
        UiModeManager uiModeManager = (UiModeManager) getContext().getSystemService(Context.UI_MODE_SERVICE);
        int uiModeType = uiModeManager != null
            ? uiModeManager.getCurrentModeType()
            : (configuration.uiMode & Configuration.UI_MODE_TYPE_MASK);
        boolean hasLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        boolean isTelevision = uiModeType == Configuration.UI_MODE_TYPE_TELEVISION || hasLeanback;
        boolean hasTouchscreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        boolean isFireOs = LandscapeOrientationLock.isFireDevice(
            Build.MANUFACTURER,
            Build.BRAND
        );
        int smallestScreenWidthDp = configuration.smallestScreenWidthDp;
        String deviceType = DeviceProfileClassifier.deviceType(
            isTelevision,
            hasLeanback,
            hasTouchscreen,
            smallestScreenWidthDp
        );
        boolean hasDpad = false;
        boolean hasHardwareKeyboard = false;
        String controllerName = null;

        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (device == null || device.isVirtual()) continue;
            int sources = device.getSources();
            boolean dpad = (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
                || (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_HDMI) == InputDevice.SOURCE_HDMI;
            if (dpad) {
                hasDpad = true;
                String displayName = DeviceProfileClassifier.displayControllerName(device.getName());
                if (controllerName == null && displayName != null) {
                    controllerName = displayName;
                }
            }
            if (device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                hasHardwareKeyboard = true;
            }
        }

        Intent voiceIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        boolean voiceInputAvailable = voiceIntent.resolveActivity(pm) != null;
        Intent batteryIntent = getContext().registerReceiver(
            null,
            new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        );
        boolean hasBattery = batteryIntent != null
            && batteryIntent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
        boolean remoteUi = DeviceProfileClassifier.usesRemoteUi(
            isTelevision,
            hasDpad,
            hasTouchscreen
        );
        controllerName = DeviceProfileClassifier.controllerNameForDevice(
            isTelevision,
            controllerName
        );

        JSObject profile = new JSObject();
        profile.put("sdk", Build.VERSION.SDK_INT);
        profile.put("deviceType", deviceType);
        profile.put("isTelevision", isTelevision);
        profile.put("isTablet", "tablet".equals(deviceType));
        profile.put("isHandheld", "handheld".equals(deviceType));
        profile.put("isFireOs", isFireOs);
        profile.put("hasLeanback", hasLeanback);
        profile.put("hasTouchscreen", hasTouchscreen);
        profile.put("hasDpad", hasDpad);
        profile.put("hasHardwareKeyboard", hasHardwareKeyboard);
        profile.put("smallestScreenWidthDp", smallestScreenWidthDp);
        profile.put("hasBattery", hasBattery);
        profile.put("remoteUi", remoteUi);
        profile.put("touchUi", DeviceProfileClassifier.usesTouchUi(deviceType, hasTouchscreen));
        profile.put("showBattery", DeviceProfileClassifier.showsBattery(deviceType, hasBattery));
        profile.put("supportsLeanbackApps", isTelevision || hasLeanback);
        profile.put("voiceInputAvailable", voiceInputAvailable);
        profile.put("controllerName", controllerName == null ? JSONObject.NULL : controllerName);
        call.resolve(profile);
    }

    @PluginMethod
    public void requestVoiceInput(PluginCall call) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, call.getString("prompt", "Speak now"));
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        if (intent.resolveActivity(getContext().getPackageManager()) == null) {
            call.reject("Voice input is not available on this device", "VOICE_INPUT_UNAVAILABLE");
            return;
        }

        try {
            startActivityForResult(call, intent, "voiceInputResult");
        } catch (Exception error) {
            call.reject("Voice input could not open", "VOICE_INPUT_FAILED", error);
        }
    }

    @ActivityCallback
    private void voiceInputResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null) {
            call.reject("Voice input was cancelled", "VOICE_INPUT_CANCELLED");
            return;
        }
        ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (matches == null || matches.isEmpty() || matches.get(0) == null || matches.get(0).trim().isEmpty()) {
            call.reject("No speech was recognized", "VOICE_INPUT_EMPTY");
            return;
        }
        JSObject response = new JSObject();
        response.put("text", matches.get(0).trim());
        call.resolve(response);
    }

    @PluginMethod
    public void resolveYouTubeChannel(PluginCall call) {
        String input = call.getString("input", "");
        final String lookupUrl;
        try {
            lookupUrl = YouTubeChannelResolver.lookupUrl(input);
        } catch (IllegalArgumentException error) {
            call.reject(error.getMessage(), "INVALID_YOUTUBE_CHANNEL");
            return;
        }
        ioExecutor.execute(() -> performYouTubeChannelResolution(call, lookupUrl));
    }

    private void performYouTubeChannelResolution(PluginCall call, String lookupUrl) {
        Request request = youtubeGetRequest(
            lookupUrl,
            "text/html,application/xhtml+xml"
        ).build();
        try (Response httpResponse = YOUTUBE_HTTP_CLIENT.newCall(request).execute()) {
            int status = httpResponse.code();
            String body = readResponseBody(httpResponse);
            if (status == 404) {
                call.reject(
                    "That exact YouTube channel was not found. Try its unique @handle.",
                    "YOUTUBE_CHANNEL_NOT_FOUND"
                );
                return;
            }
            if (status == 429) {
                call.reject(
                    "YouTube is temporarily limiting channel verification. Try again shortly.",
                    "YOUTUBE_CHANNEL_RATE_LIMITED"
                );
                return;
            }
            if (status < 200 || status >= 300) {
                call.reject("YouTube channel verification could not connect", "YOUTUBE_CHANNEL_LOOKUP_FAILED");
                return;
            }

            YouTubeChannelResolver.ResolvedChannel resolved =
                YouTubeChannelResolver.parseVerifiedPage(body);
            resolveYouTubeChannelResult(
                call,
                resolved.channelId,
                resolved.canonicalUrl,
                resolved.title,
                resolved.thumbnailUrl
            );
        } catch (IllegalArgumentException error) {
            call.reject(
                error.getMessage() + ". Try the channel's unique @handle.",
                "YOUTUBE_CHANNEL_NOT_FOUND"
            );
        } catch (Exception error) {
            call.reject(
                "YouTube channel verification could not connect. Check this device's network.",
                "YOUTUBE_CHANNEL_LOOKUP_FAILED",
                error
            );
        }
    }

    private void resolveYouTubeChannelResult(
        PluginCall call,
        String channelId,
        String canonicalUrl,
        String rawTitle,
        String thumbnailUrl
    ) {
        JSObject channel = new JSObject();
        channel.put("kind", "channel");
        channel.put("sourceId", channelId);
        channel.put("sourceUrl", canonicalUrl);
        String title = plainText(rawTitle);
        channel.put("title", title);
        channel.put("channelTitle", title);
        channel.put("thumbnailUrl", thumbnailUrl == null ? JSONObject.NULL : thumbnailUrl);
        JSObject response = new JSObject();
        response.put("channel", channel);
        call.resolve(response);
    }

    @PluginMethod
    public void getYouTubeChannelVideos(PluginCall call) {
        String channelId = call.getString("channelId", "").trim();
        String pageToken = call.getString("pageToken", "").trim();
        final String catalogUrl;
        try {
            catalogUrl = YouTubeChannelCatalogParser.pageUrl(channelId);
        } catch (IllegalArgumentException error) {
            call.reject(error.getMessage(), "INVALID_YOUTUBE_CHANNEL");
            return;
        }

        if (!pageToken.isEmpty()) {
            final YouTubeCatalogCursor cursor;
            synchronized (youtubeCatalogCursors) {
                cursor = youtubeCatalogCursors.get(pageToken);
            }
            if (cursor == null || !channelId.equals(cursor.channelId)) {
                call.reject("This channel page expired. Refresh the channel and try again.", "YOUTUBE_CHANNEL_PAGE_EXPIRED");
                return;
            }
            ioExecutor.execute(() -> performYouTubeChannelContinuationLookup(call, pageToken, cursor));
            return;
        }

        ioExecutor.execute(() -> performYouTubeChannelCatalogLookup(call, channelId, catalogUrl));
    }

    private void performYouTubeChannelCatalogLookup(
        PluginCall call,
        String channelId,
        String catalogUrl
    ) {
        Request request = youtubeGetRequest(
            catalogUrl,
            "text/html,application/xhtml+xml"
        ).build();
        try (Response httpResponse = YOUTUBE_HTTP_CLIENT.newCall(request).execute()) {
            int status = httpResponse.code();
            String body = readResponseBody(httpResponse);
            if (status < 200 || status >= 300) {
                throw new IllegalArgumentException("YouTube channel catalog is temporarily unavailable");
            }

            YouTubeChannelCatalogParser.InitialPage page =
                YouTubeChannelCatalogParser.parseInitialPage(body, channelId);
            String title = "YouTube Channel";
            try {
                title = YouTubeChannelResolver.parseVerifiedPage(body).title;
            } catch (IllegalArgumentException ignored) {}
            resolveYouTubeCatalogPage(
                call,
                channelId,
                title,
                page,
                page.apiKey,
                page.clientVersion,
                "channel-page"
            );
        } catch (Exception catalogError) {
            // Public YouTube page markup changes occasionally. Preserve the
            // stable recent-feed experience while the full catalog parser is
            // repaired instead of leaving the channel unusable.
            performYouTubeChannelFeedLookup(
                call,
                channelId,
                YouTubeChannelFeedParser.feedUrl(channelId)
            );
        }
    }

    private void performYouTubeChannelContinuationLookup(
        PluginCall call,
        String pageToken,
        YouTubeCatalogCursor cursor
    ) {
        try {
            String clientVersion = YouTubeChannelCatalogParser.validateClientVersion(cursor.clientVersion);
            String continuation = YouTubeChannelCatalogParser.validateContinuation(cursor.continuation);

            JSONObject client = new JSONObject();
            client.put("clientName", "MWEB");
            client.put("clientVersion", clientVersion);
            client.put("hl", "en");
            client.put("gl", "US");
            JSONObject context = new JSONObject();
            context.put("client", client);
            JSONObject payload = new JSONObject();
            payload.put("context", context);
            payload.put("continuation", continuation);
            RequestBody requestBody = RequestBody.create(
                MediaType.parse("application/json; charset=UTF-8"),
                payload.toString()
            );
            Request request = youtubeRequest(
                YouTubeChannelCatalogParser.continuationUrl(cursor.apiKey),
                "application/json"
            )
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", YouTubeChannelCatalogParser.pageUrl(cursor.channelId))
                .header("X-YouTube-Client-Name", "2")
                .header("X-YouTube-Client-Version", clientVersion)
                .post(requestBody)
                .build();

            try (Response httpResponse = YOUTUBE_HTTP_CLIENT.newCall(request).execute()) {
                int status = httpResponse.code();
                String body = readResponseBody(httpResponse);
                if (status == 429) {
                    call.reject("YouTube is temporarily limiting channel browsing", "YOUTUBE_CHANNEL_RATE_LIMITED");
                    return;
                }
                if (status < 200 || status >= 300) {
                    call.reject("OpenPanel could not load older channel videos", "YOUTUBE_CHANNEL_PAGE_FAILED");
                    return;
                }

                YouTubeChannelCatalogParser.CatalogPage page =
                    YouTubeChannelCatalogParser.parseContinuation(body);
                synchronized (youtubeCatalogCursors) {
                    youtubeCatalogCursors.remove(pageToken);
                }
                resolveYouTubeCatalogPage(
                    call,
                    cursor.channelId,
                    "",
                    page,
                    cursor.apiKey,
                    clientVersion,
                    "channel-page"
                );
            }
        } catch (IllegalArgumentException error) {
            call.reject(error.getMessage(), "YOUTUBE_CHANNEL_PAGE_INVALID");
        } catch (Exception error) {
            call.reject(
                "OpenPanel could not load older channel videos. Check this device's network.",
                "YOUTUBE_CHANNEL_PAGE_FAILED",
                error
            );
        }
    }

    private void resolveYouTubeCatalogPage(
        PluginCall call,
        String channelId,
        String channelTitle,
        YouTubeChannelCatalogParser.CatalogPage page,
        String apiKey,
        String clientVersion,
        String source
    ) {
        JSArray videos = new JSArray();
        for (YouTubeChannelCatalogParser.CatalogVideo video : page.videos) {
            JSObject item = new JSObject();
            item.put("videoId", video.videoId);
            item.put("title", plainText(video.title));
            item.put("thumbnailUrl", video.thumbnailUrl);
            item.put("publishedAt", video.publishedAt);
            videos.put(item);
        }
        String nextPageToken = storeYouTubeCatalogCursor(
            channelId,
            apiKey,
            clientVersion,
            page.continuation
        );
        JSObject response = new JSObject();
        response.put("channelTitle", plainText(channelTitle));
        response.put("videos", videos);
        response.put("nextPageToken", nextPageToken == null ? JSONObject.NULL : nextPageToken);
        response.put("hasMore", nextPageToken != null);
        response.put("catalogComplete", nextPageToken == null);
        response.put("catalogSource", source);
        call.resolve(response);
    }

    private String storeYouTubeCatalogCursor(
        String channelId,
        String apiKey,
        String clientVersion,
        String continuation
    ) {
        if (continuation == null || continuation.isEmpty()) return null;
        String cursorId = UUID.randomUUID().toString();
        YouTubeCatalogCursor cursor = new YouTubeCatalogCursor(
            channelId,
            apiKey,
            clientVersion,
            YouTubeChannelCatalogParser.validateContinuation(continuation)
        );
        synchronized (youtubeCatalogCursors) {
            if (youtubeCatalogCursors.size() >= 64) {
                Iterator<String> it = youtubeCatalogCursors.keySet().iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
            youtubeCatalogCursors.put(cursorId, cursor);
        }
        return cursorId;
    }

    // Deliberately spoofs a common mobile-Chrome browser so YouTube's public
    // channel/feed pages return the standard web markup the resolvers parse. The
    // Chrome major version is pinned on purpose; bump it only when YouTube starts
    // gating on a newer build. This is the sole definition of this UA.
    private static String youtubeWebUserAgent() {
        return "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE
            + ") AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36";
    }

    private static Request.Builder youtubeGetRequest(String url, String accept) {
        return youtubeRequest(url, accept).get();
    }

    private static Request.Builder youtubeRequest(String url, String accept) {
        return new Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("Accept-Encoding", "identity")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", youtubeWebUserAgent());
    }

    private void performYouTubeChannelFeedLookup(
        PluginCall call,
        String channelId,
        String feedUrl
    ) {
        Request request = youtubeGetRequest(
            feedUrl,
            "application/atom+xml,application/xml,text/xml"
        ).build();
        try (Response httpResponse = YOUTUBE_HTTP_CLIENT.newCall(request).execute()) {
            int status = httpResponse.code();
            String body = readResponseBody(httpResponse);
            if (status == 404) {
                call.reject("This channel's recent videos are unavailable", "YOUTUBE_CHANNEL_FEED_NOT_FOUND");
                return;
            }
            if (status == 429) {
                call.reject("YouTube is temporarily limiting channel browsing", "YOUTUBE_CHANNEL_RATE_LIMITED");
                return;
            }
            if (status < 200 || status >= 300) {
                call.reject("OpenPanel could not load this channel's videos", "YOUTUBE_CHANNEL_FEED_FAILED");
                return;
            }

            YouTubeChannelFeedParser.ParsedFeed parsed =
                YouTubeChannelFeedParser.parse(body, channelId);
            JSArray videos = new JSArray();
            for (YouTubeChannelFeedParser.FeedVideo video : parsed.videos) {
                JSObject item = new JSObject();
                item.put("videoId", video.videoId);
                item.put("title", plainText(video.title));
                item.put("thumbnailUrl", video.thumbnailUrl);
                item.put("publishedAt", video.publishedAt);
                videos.put(item);
            }
            JSObject response = new JSObject();
            response.put("channelTitle", plainText(parsed.channelTitle));
            response.put("videos", videos);
            response.put("nextPageToken", JSONObject.NULL);
            response.put("hasMore", false);
            response.put("catalogComplete", false);
            response.put("catalogSource", "recent-feed");
            call.resolve(response);
        } catch (IllegalArgumentException error) {
            call.reject(error.getMessage(), "YOUTUBE_CHANNEL_FEED_INVALID");
        } catch (Exception error) {
            call.reject(
                "OpenPanel could not load this channel's videos. Check this device's network.",
                "YOUTUBE_CHANNEL_FEED_FAILED",
                error
            );
        }
    }

    private static String readResponseBody(Response response) throws Exception {
        if (response.body() == null) return "";
        return readStream(response.body().byteStream());
    }

    private static String readStream(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Re-add the stripped line separator so values that span line
                // breaks aren't silently concatenated (the channel-resolver and
                // feed regexes depend on the original newlines being present).
                body.append(line).append('\n');
                if (body.length() > 2_000_000) {
                    throw new IllegalArgumentException("YouTube returned an unexpectedly large channel page");
                }
            }
        }
        return body.toString();
    }

    private static String plainText(String value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString();
        }
        //noinspection deprecation
        return Html.fromHtml(value).toString();
    }

    private static final class YouTubeCatalogCursor {
        final String channelId;
        final String apiKey;
        final String clientVersion;
        final String continuation;

        YouTubeCatalogCursor(
            String channelId,
            String apiKey,
            String clientVersion,
            String continuation
        ) {
            this.channelId = channelId;
            this.apiKey = apiKey;
            this.clientVersion = clientVersion;
            this.continuation = continuation;
        }
    }

    @Override
    public void load() {
        // Emit an event whenever the Bluetooth adapter finishes turning on/off,
        // so the UI reflects reality even when the change came from a system
        // confirmation dialog (which can lag behind the user's tap).
        btStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_OFF) {
                    // A scan in progress won't get ACTION_DISCOVERY_FINISHED once
                    // the adapter powers off — settle it now so it can't hang.
                    if (state == BluetoothAdapter.STATE_OFF) finishBtScan();
                    JSObject data = new JSObject();
                    data.put("enabled", state == BluetoothAdapter.STATE_ON);
                    notifyListeners("bluetoothStateChanged", data);
                }
            }
        };
        getContext().registerReceiver(btStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

        // Push battery changes the instant they happen. ACTION_BATTERY_CHANGED
        // fires immediately when the charger is plugged/unplugged (the status
        // extra flips) as well as on level changes, so the header no longer
        // waits for the 15s status poll and can't show a stale "charging" state
        // for a second or two after the charger is pulled.
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                notifyListeners("batteryChanged", readBattery(intent));
            }
        };
        getContext().registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override
    protected void handleOnDestroy() {
        // Unregisters btScanReceiver, cancels the watchdog, and settles any
        // in-flight scan call.
        finishBtScan();
        if (btStateReceiver != null) {
            try { getContext().unregisterReceiver(btStateReceiver); } catch (Exception ignored) {}
            btStateReceiver = null;
        }
        if (bondReceiver != null) {
            try { getContext().unregisterReceiver(bondReceiver); } catch (Exception ignored) {}
            bondReceiver = null;
        }
        // A pairing may still be in flight (system dialog open) at teardown; settle
        // its kept-alive call so the JS Promise never hangs. Mirrors finishBtScan.
        settlePendingPairCall("Pairing was interrupted", "PAIR_INTERRUPTED");
        if (batteryReceiver != null) {
            try { getContext().unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
            batteryReceiver = null;
        }
        ioExecutor.shutdownNow();
    }

    // ---------- Device Health + reversible debloating ----------

    private SharedPreferences deviceHealthPrefs() {
        return getContext().getSharedPreferences(DEVICE_HEALTH_PREFS, Context.MODE_PRIVATE);
    }

    private boolean isOpenPanelDeviceOwner() {
        DevicePolicyManager policy = dpm();
        return policy != null && policy.isDeviceOwnerApp(getContext().getPackageName());
    }

    private Set<String> readTrackedPackages(String key) {
        return new HashSet<>(deviceHealthPrefs().getStringSet(key, Collections.emptySet()));
    }

    private void writeTrackedPackages(String key, Set<String> packages) {
        deviceHealthPrefs().edit().putStringSet(key, new HashSet<>(packages)).apply();
    }

    private String detectedDebloatProfile() {
        return DebloatCatalog.profileFor(Build.MANUFACTURER, Build.BRAND);
    }

    private ApplicationInfo findApplication(String packageName) {
        try {
            ApplicationInfo info = getContext().getPackageManager().getApplicationInfo(
                packageName,
                PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES
            );
            return (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0 ? info : null;
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    private boolean isPackageHidden(String packageName) {
        DevicePolicyManager policy = dpm();
        if (policy == null || !isOpenPanelDeviceOwner()) return false;
        try {
            return policy.isApplicationHidden(
                OpenPanelDeviceAdminReceiver.getComponentName(getContext()), packageName);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isSystemApplication(ApplicationInfo info) {
        return info != null && (info.flags
            & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    private JSObject debloatAppJson(DebloatCatalog.Rule rule, Set<String> tracked) {
        PackageManager packageManager = getContext().getPackageManager();
        ApplicationInfo info = findApplication(rule.packageName);
        if (info == null) return null;

        boolean hidden = isPackageHidden(rule.packageName);
        boolean managedByOpenPanel = tracked.contains(rule.packageName);
        JSObject app = new JSObject();
        app.put("packageName", rule.packageName);
        app.put("label", rule.label);
        app.put("installedLabel", packageManager.getApplicationLabel(info).toString());
        app.put("category", rule.category);
        app.put("reason", rule.reason);
        app.put("profile", rule.profile);
        app.put("isSystem", isSystemApplication(info));
        app.put("enabled", info.enabled);
        app.put("hidden", hidden);
        app.put("managedByOpenPanel", managedByOpenPanel);
        app.put("canUninstall", !isSystemApplication(info));
        app.put("status", hidden
            ? "hidden"
            : (!info.enabled ? "disabled-externally" : "active"));
        return app;
    }

    private JSArray installedDebloatApps() {
        Set<String> tracked = readTrackedPackages(PREF_HIDDEN_PACKAGES);
        JSArray apps = new JSArray();
        for (DebloatCatalog.Rule rule : DebloatCatalog.rulesFor(
                Build.MANUFACTURER, Build.BRAND)) {
            JSObject app = debloatAppJson(rule, tracked);
            if (app != null) apps.put(app);
        }
        return apps;
    }

    @PluginMethod
    public void getDeviceHealth(PluginCall call) {
        ioExecutor.execute(() -> {
            ActivityManager activityManager = (ActivityManager) getContext()
                .getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            if (activityManager != null) activityManager.getMemoryInfo(memory);
            long usedRam = Math.max(0L, memory.totalMem - memory.availMem);

            StatFs storage = new StatFs(Environment.getDataDirectory().getPath());
            long totalStorage = storage.getTotalBytes();
            long availableStorage = storage.getAvailableBytes();
            long usedStorage = Math.max(0L, totalStorage - availableStorage);

            int installedBloat = 0;
            int activeBloat = 0;
            int hiddenBloat = 0;
            for (DebloatCatalog.Rule rule : DebloatCatalog.rulesFor(
                    Build.MANUFACTURER, Build.BRAND)) {
                ApplicationInfo info = findApplication(rule.packageName);
                if (info == null) continue;
                installedBloat++;
                if (isPackageHidden(rule.packageName)) hiddenBloat++;
                else if (info.enabled) activeBloat++;
            }

            DevicePolicyManager policy = dpm();
            boolean deviceOwner = isOpenPanelDeviceOwner();
            boolean arborXrManaged = policy != null && policy.isDeviceOwnerApp(ARBORXR_DPC);
            JSObject result = new JSObject();
            result.put("manufacturer", Build.MANUFACTURER);
            result.put("brand", Build.BRAND);
            result.put("model", Build.MODEL);
            result.put("device", Build.DEVICE);
            result.put("product", Build.PRODUCT);
            result.put("androidVersion", Build.VERSION.RELEASE);
            result.put("sdk", Build.VERSION.SDK_INT);
            result.put("profile", detectedDebloatProfile());
            result.put("ramTotalBytes", memory.totalMem);
            result.put("ramAvailableBytes", memory.availMem);
            result.put("ramUsedBytes", usedRam);
            result.put("ramUsedPercent", memory.totalMem > 0
                ? Math.round((usedRam * 100.0) / memory.totalMem) : 0);
            result.put("storageTotalBytes", totalStorage);
            result.put("storageAvailableBytes", availableStorage);
            result.put("storageUsedBytes", usedStorage);
            result.put("storageUsedPercent", totalStorage > 0
                ? Math.round((usedStorage * 100.0) / totalStorage) : 0);
            result.put("deviceOwner", deviceOwner);
            result.put("arborXrManaged", arborXrManaged);
            result.put("canManageApps", deviceOwner);
            result.put("canManageNotifications",
                deviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU);
            result.put("manageNotifications",
                deviceHealthPrefs().getBoolean(PREF_MANAGE_NOTIFICATIONS, true));
            result.put("installedBloatCount", installedBloat);
            result.put("activeBloatCount", activeBloat);
            result.put("hiddenBloatCount", hiddenBloat);
            result.put("notificationManagedCount",
                readTrackedPackages(PREF_NOTIFICATION_PACKAGES).size());
            call.resolve(result);
        });
    }

    @PluginMethod
    public void getDebloatApps(PluginCall call) {
        ioExecutor.execute(() -> {
            JSObject result = new JSObject();
            result.put("profile", detectedDebloatProfile());
            result.put("apps", installedDebloatApps());
            call.resolve(result);
        });
    }

    private JSObject debloatCapabilityResult(boolean available, String message) {
        JSObject result = new JSObject();
        result.put("available", available);
        result.put("deviceOwner", isOpenPanelDeviceOwner());
        DevicePolicyManager policy = dpm();
        result.put("arborXrManaged", policy != null && policy.isDeviceOwnerApp(ARBORXR_DPC));
        result.put("message", message);
        return result;
    }

    private boolean setPackageHiddenByOpenPanel(
        String packageName,
        boolean hidden,
        Set<String> tracked
    ) {
        if (DebloatCatalog.isProtectedPackage(packageName)) return false;
        DebloatCatalog.Rule rule = DebloatCatalog.findRule(
            packageName, Build.MANUFACTURER, Build.BRAND);
        if (rule == null || findApplication(packageName) == null) return false;
        DevicePolicyManager policy = dpm();
        if (policy == null || !isOpenPanelDeviceOwner()) return false;
        try {
            boolean changed = policy.setApplicationHidden(
                OpenPanelDeviceAdminReceiver.getComponentName(getContext()), packageName, hidden);
            if (changed || isPackageHidden(packageName) == hidden) {
                if (hidden) tracked.add(packageName);
                else tracked.remove(packageName);
                return true;
            }
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "Could not change package visibility for " + packageName, error);
        }
        return false;
    }

    @PluginMethod
    public void applyRecommendedDebloat(PluginCall call) {
        boolean manageNotifications = Boolean.TRUE.equals(
            call.getBoolean("manageNotifications", Boolean.TRUE));
        deviceHealthPrefs().edit()
            .putBoolean(PREF_MANAGE_NOTIFICATIONS, manageNotifications)
            .apply();
        if (!isOpenPanelDeviceOwner()) {
            call.resolve(debloatCapabilityResult(false,
                "OpenPanel must be Device Owner to manage packages. ArborXR-managed devices must use an ArborXR policy."));
            return;
        }

        ioExecutor.execute(() -> {
            Set<String> tracked = readTrackedPackages(PREF_HIDDEN_PACKAGES);
            int changed = 0;
            int skipped = 0;
            for (DebloatCatalog.Rule rule : DebloatCatalog.rulesFor(
                    Build.MANUFACTURER, Build.BRAND)) {
                ApplicationInfo info = findApplication(rule.packageName);
                if (info == null || DebloatCatalog.isProtectedPackage(rule.packageName)) continue;
                if (isPackageHidden(rule.packageName)) {
                    skipped++;
                    continue;
                }
                if (setPackageHiddenByOpenPanel(rule.packageName, true, tracked)) changed++;
                else skipped++;
            }
            writeTrackedPackages(PREF_HIDDEN_PACKAGES, tracked);
            JSObject notificationResult = applyNotificationPolicy(manageNotifications);
            JSObject result = debloatCapabilityResult(true,
                "Recommended apps were hidden reversibly.");
            result.put("changed", changed);
            result.put("skipped", skipped);
            result.put("apps", installedDebloatApps());
            result.put("notifications", notificationResult);
            call.resolve(result);
        });
    }

    @PluginMethod
    public void setDebloatPackageState(PluginCall call) {
        String packageName = call.getString("packageName");
        boolean hidden = Boolean.TRUE.equals(call.getBoolean("hidden", Boolean.TRUE));
        if (packageName == null || DebloatCatalog.findRule(
                packageName, Build.MANUFACTURER, Build.BRAND) == null
                || DebloatCatalog.isProtectedPackage(packageName)) {
            call.reject("Package is not in the detected, reviewed debloat policy", "PACKAGE_NOT_ALLOWED");
            return;
        }
        if (!isOpenPanelDeviceOwner()) {
            call.resolve(debloatCapabilityResult(false,
                "OpenPanel must be Device Owner to change package visibility."));
            return;
        }
        ioExecutor.execute(() -> {
            Set<String> tracked = readTrackedPackages(PREF_HIDDEN_PACKAGES);
            boolean applied = setPackageHiddenByOpenPanel(packageName, hidden, tracked);
            writeTrackedPackages(PREF_HIDDEN_PACKAGES, tracked);
            JSObject result = debloatCapabilityResult(applied,
                applied ? (hidden ? "App hidden." : "App restored.")
                    : "Android did not apply the requested package change.");
            DebloatCatalog.Rule rule = DebloatCatalog.findRule(
                packageName, Build.MANUFACTURER, Build.BRAND);
            result.put("app", rule == null ? JSONObject.NULL : debloatAppJson(rule, tracked));
            call.resolve(result);
        });
    }

    @PluginMethod
    public void restoreDebloatApps(PluginCall call) {
        if (!isOpenPanelDeviceOwner()) {
            call.resolve(debloatCapabilityResult(false,
                "OpenPanel must still be Device Owner to restore managed apps."));
            return;
        }
        ioExecutor.execute(() -> {
            Set<String> tracked = readTrackedPackages(PREF_HIDDEN_PACKAGES);
            Set<String> remaining = new HashSet<>(tracked);
            int restored = 0;
            for (String packageName : new HashSet<>(tracked)) {
                if (setPackageHiddenByOpenPanel(packageName, false, remaining)) restored++;
            }
            writeTrackedPackages(PREF_HIDDEN_PACKAGES, remaining);
            JSObject result = debloatCapabilityResult(true,
                "Apps hidden by OpenPanel were restored.");
            result.put("restored", restored);
            result.put("remaining", remaining.size());
            result.put("apps", installedDebloatApps());
            call.resolve(result);
        });
    }

    private String resolvedHomePackage() {
        return DeviceAccess.resolvedHomePackage(getContext());
    }

    private boolean isNotificationPolicyProtected(String packageName) {
        if (packageName == null
                || packageName.equals(getContext().getPackageName())
                || packageName.equals(resolvedHomePackage())
                || DebloatCatalog.isProtectedPackage(packageName)) {
            return true;
        }
        ApplicationInfo info = findApplication(packageName);
        if (info == null) return true;
        // System apps can provide setup, documents, networking, telephony, and
        // other critical surfaces whose notifications are operational rather
        // than promotional. Only the reviewed consumer catalog is eligible
        // when such an app happens to be preinstalled as a system package.
        return isSystemApplication(info) && DebloatCatalog.findRule(
            packageName, Build.MANUFACTURER, Build.BRAND) == null;
    }

    private boolean requestsPostNotifications(String packageName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false;
        try {
            PackageInfo info = getContext().getPackageManager().getPackageInfo(
                packageName, PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions == null) return false;
            for (String permission : info.requestedPermissions) {
                if (Manifest.permission.POST_NOTIFICATIONS.equals(permission)) return true;
            }
        } catch (PackageManager.NameNotFoundException ignored) {}
        return false;
    }

    private JSObject applyNotificationPolicy(boolean enabled) {
        NotificationBlockPolicy.setEnabled(getContext(), enabled);
        boolean notificationAccess = DeviceAccess.isNotificationListenerEnabled(getContext());
        if (enabled && notificationAccess) {
            OpenPanelNotificationListenerService.applyNow();
        }
        JSObject result = debloatCapabilityResult(notificationAccess,
            enabled
                ? (notificationAccess
                    ? "Nonessential notifications are blocked."
                    : "Grant Notification Access to block nonessential notifications on this device.")
                : "OpenPanel notification blocking is off.");
        result.put("enabled", enabled);
        result.put("notificationAccess", notificationAccess);
        if (!isOpenPanelDeviceOwner() || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            result.put("changed", 0);
            result.put("remaining", readTrackedPackages(PREF_NOTIFICATION_PACKAGES).size());
            return result;
        }

        DevicePolicyManager policy = dpm();
        PackageManager packageManager = getContext().getPackageManager();
        ComponentName admin = OpenPanelDeviceAdminReceiver.getComponentName(getContext());
        Set<String> tracked = readTrackedPackages(PREF_NOTIFICATION_PACKAGES);
        int changed = 0;

        if (enabled) {
            // A policy update, launcher change, or OEM update can make a
            // previously managed package protected. Restore it before applying
            // the current policy so tracked permissions never get stranded.
            for (String packageName : new HashSet<>(tracked)) {
                if (findApplication(packageName) == null) {
                    tracked.remove(packageName);
                    continue;
                }
                if (!isNotificationPolicyProtected(packageName)) continue;
                try {
                    if (policy.setPermissionGrantState(admin, packageName,
                            Manifest.permission.POST_NOTIFICATIONS,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)) {
                        tracked.remove(packageName);
                        changed++;
                    }
                } catch (RuntimeException error) {
                    Log.w(LOG_TAG, "Could not restore protected notifications for "
                        + packageName, error);
                }
            }
            for (ApplicationInfo info : packageManager.getInstalledApplications(
                    PackageManager.MATCH_DISABLED_COMPONENTS)) {
                String packageName = info.packageName;
                if (isNotificationPolicyProtected(packageName)
                        || !requestsPostNotifications(packageName)) continue;
                try {
                    if (packageManager.checkPermission(Manifest.permission.POST_NOTIFICATIONS,
                            packageName) != PackageManager.PERMISSION_GRANTED) continue;
                    if (policy.setPermissionGrantState(admin, packageName,
                            Manifest.permission.POST_NOTIFICATIONS,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED)) {
                        tracked.add(packageName);
                        changed++;
                    }
                } catch (RuntimeException error) {
                    Log.w(LOG_TAG, "Could not suppress notifications for " + packageName, error);
                }
            }
        } else {
            for (String packageName : new HashSet<>(tracked)) {
                if (findApplication(packageName) == null) {
                    tracked.remove(packageName);
                    continue;
                }
                try {
                    if (policy.setPermissionGrantState(admin, packageName,
                            Manifest.permission.POST_NOTIFICATIONS,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)) {
                        tracked.remove(packageName);
                        changed++;
                    }
                } catch (RuntimeException error) {
                    Log.w(LOG_TAG, "Could not restore notifications for " + packageName, error);
                }
            }
        }

        writeTrackedPackages(PREF_NOTIFICATION_PACKAGES, tracked);
        result.put("available", true);
        result.put("message", enabled
            ? "Notifications were disabled for eligible apps."
            : "Notification grants changed by OpenPanel were restored.");
        result.put("changed", changed);
        result.put("remaining", tracked.size());
        return result;
    }

    @PluginMethod
    public void setNotificationManagement(PluginCall call) {
        boolean enabled = Boolean.TRUE.equals(call.getBoolean("enabled", Boolean.TRUE));
        ioExecutor.execute(() -> call.resolve(applyNotificationPolicy(enabled)));
    }

    @PluginMethod
    public void requestUninstallPackage(PluginCall call) {
        String packageName = call.getString("packageName");
        DebloatCatalog.Rule rule = DebloatCatalog.findRule(
            packageName, Build.MANUFACTURER, Build.BRAND);
        ApplicationInfo info = packageName == null ? null : findApplication(packageName);
        if (rule == null || info == null || DebloatCatalog.isProtectedPackage(packageName)) {
            call.reject("Package is not in the detected, reviewed debloat policy", "PACKAGE_NOT_ALLOWED");
            return;
        }
        if (isSystemApplication(info)) {
            call.reject("System apps can only be hidden and restored", "SYSTEM_APP");
            return;
        }
        Set<String> tracked = readTrackedPackages(PREF_HIDDEN_PACKAGES);
        if (tracked.contains(packageName) && isOpenPanelDeviceOwner()) {
            setPackageHiddenByOpenPanel(packageName, false, tracked);
            writeTrackedPackages(PREF_HIDDEN_PACKAGES, tracked);
        }
        Intent uninstall = new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
            Uri.parse("package:" + packageName));
        uninstall.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        try {
            unpinIfPinned();
            startActivityForResult(call, uninstall, "uninstallPackageResult");
        } catch (RuntimeException error) {
            call.reject("Could not open Android's uninstall confirmation",
                "UNINSTALL_FAILED", error);
        }
    }

    @ActivityCallback
    private void uninstallPackageResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        String packageName = call.getString("packageName");
        JSObject response = new JSObject();
        response.put("packageName", packageName);
        response.put("uninstalled", packageName != null && findApplication(packageName) == null);
        response.put("resultCode", result.getResultCode());
        call.resolve(response);
    }

    // ---------- TV DNS + network privacy ----------

    private String alwaysOnVpnPackage() {
        DevicePolicyManager policy = dpm();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && policy != null && isOpenPanelDeviceOwner()) {
            try {
                String managed = policy.getAlwaysOnVpnPackage(
                    OpenPanelDeviceAdminReceiver.getComponentName(getContext()));
                if (managed != null && !managed.trim().isEmpty()) return managed;
            } catch (RuntimeException ignored) {}
        }
        try {
            String configured = Settings.Secure.getString(
                getContext().getContentResolver(), "always_on_vpn_app");
            return configured == null || configured.trim().isEmpty() ? null : configured;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean isVpnLockdownEnabled() {
        DevicePolicyManager policy = dpm();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && policy != null && isOpenPanelDeviceOwner()) {
            try {
                return policy.isAlwaysOnVpnLockdownEnabled(
                    OpenPanelDeviceAdminReceiver.getComponentName(getContext()));
            } catch (RuntimeException ignored) {}
        }
        try {
            return Settings.Secure.getInt(
                getContext().getContentResolver(), "always_on_vpn_lockdown", 0) == 1;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private JSObject readDnsFilterStatus() {
        PackageManager packageManager = getContext().getPackageManager();
        ApplicationInfo filterInfo = findApplication(NetworkPrivacyState.DNS_FILTER_PACKAGE);
        boolean installed = filterInfo != null;
        boolean enabled = installed && filterInfo.enabled;
        int filterUid = installed ? filterInfo.uid : -1;
        boolean vpnActive = false;
        boolean vpnOwnedByFilter = false;
        ConnectivityManager connectivity = (ConnectivityManager) getContext()
            .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity != null) {
            try {
                for (Network network : connectivity.getAllNetworks()) {
                    NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                    if (capabilities == null
                            || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        continue;
                    }
                    vpnActive = true;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            && capabilities.getOwnerUid() == filterUid) {
                        vpnOwnedByFilter = true;
                    }
                }
            } catch (RuntimeException error) {
                Log.w(LOG_TAG, "Could not inspect VPN networks", error);
            }
        }

        String alwaysOnPackage = alwaysOnVpnPackage();
        boolean alwaysOn = NetworkPrivacyState.DNS_FILTER_PACKAGE.equals(alwaysOnPackage);
        vpnOwnedByFilter = NetworkPrivacyState.isVpnOwnedByFilter(
            vpnActive, vpnOwnedByFilter, alwaysOn);
        boolean filterActive = NetworkPrivacyState.isFilterActive(
            installed, enabled, vpnOwnedByFilter);
        String privateDnsMode = NetworkPrivacyState.normalizePrivateDnsMode(
            Settings.Global.getString(getContext().getContentResolver(), "private_dns_mode"));
        String privateDnsHost = Settings.Global.getString(
            getContext().getContentResolver(), "private_dns_specifier");
        if (privateDnsHost != null && privateDnsHost.trim().isEmpty()) privateDnsHost = null;

        String versionName = null;
        if (installed) {
            try {
                PackageInfo packageInfo = packageManager.getPackageInfo(
                    NetworkPrivacyState.DNS_FILTER_PACKAGE, 0);
                versionName = packageInfo.versionName;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }

        JSObject result = new JSObject();
        result.put("filterPackage", NetworkPrivacyState.DNS_FILTER_PACKAGE);
        result.put("versionName", versionName == null ? JSONObject.NULL : versionName);
        result.put("installed", installed);
        result.put("enabled", enabled);
        result.put("vpnActive", vpnActive);
        result.put("filterActive", filterActive);
        result.put("alwaysOn", alwaysOn);
        result.put("lockdown", alwaysOn && isVpnLockdownEnabled());
        result.put("privateDnsMode", privateDnsMode);
        result.put("privateDnsHost",
            privateDnsHost == null ? JSONObject.NULL : privateDnsHost);
        result.put("privateDnsMayBypassFilter",
            NetworkPrivacyState.privateDnsMayBypassFilter(filterActive, privateDnsMode));
        result.put("canManageAlwaysOn",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isOpenPanelDeviceOwner());
        DevicePolicyManager policy = dpm();
        result.put("arborXrManaged",
            policy != null && policy.isDeviceOwnerApp(ARBORXR_DPC));
        return result;
    }

    @PluginMethod
    public void getDnsFilterStatus(PluginCall call) {
        ioExecutor.execute(() -> call.resolve(readDnsFilterStatus()));
    }

    @PluginMethod
    public void setDnsFilterAlwaysOn(PluginCall call) {
        boolean enabled = Boolean.TRUE.equals(call.getBoolean("enabled", Boolean.TRUE));
        DevicePolicyManager policy = dpm();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            call.reject("Always-on VPN requires Android 7 or newer", "VPN_UNSUPPORTED");
            return;
        }
        if (policy == null || !isOpenPanelDeviceOwner()) {
            call.reject(
                "OpenPanel must be Device Owner to manage always-on VPN. Use the TV VPN settings or the active MDM policy.",
                "NOT_DEVICE_OWNER");
            return;
        }
        if (enabled && findApplication(NetworkPrivacyState.DNS_FILTER_PACKAGE) == null) {
            call.reject("personalDNSfilter is not installed", "DNS_FILTER_NOT_INSTALLED");
            return;
        }
        ComponentName admin = OpenPanelDeviceAdminReceiver.getComponentName(getContext());
        try {
            // Do not enable lockdown here. A resolver or blocklist mistake must
            // not strand a TV offline; an MDM can deliberately add lockdown.
            policy.setAlwaysOnVpnPackage(
                admin,
                enabled ? NetworkPrivacyState.DNS_FILTER_PACKAGE : null,
                false);
            call.resolve(readDnsFilterStatus());
        } catch (PackageManager.NameNotFoundException error) {
            call.reject("This personalDNSfilter build does not support always-on VPN",
                "ALWAYS_ON_UNSUPPORTED", error);
        } catch (RuntimeException error) {
            call.reject("Android could not change always-on DNS filtering: "
                + error.getMessage(), "ALWAYS_ON_FAILED", error);
        }
    }

    @PluginMethod
    public void openDnsFilter(PluginCall call) {
        Intent intent = getContext().getPackageManager().getLaunchIntentForPackage(
            NetworkPrivacyState.DNS_FILTER_PACKAGE);
        if (intent == null) {
            call.reject("personalDNSfilter is not installed", "DNS_FILTER_NOT_INSTALLED");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            unpinIfPinned();
            getContext().startActivity(intent);
            call.resolve();
        } catch (RuntimeException error) {
            call.reject("Could not open personalDNSfilter", "DNS_FILTER_LAUNCH_FAILED", error);
        }
    }

    @PluginMethod
    public void openVpnSettings(PluginCall call) {
        launchSettings(call, new Intent(Settings.ACTION_VPN_SETTINGS));
    }

    @PluginMethod
    public void openPrivateDnsSettings(PluginCall call) {
        Intent intent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? new Intent("android.settings.PRIVATE_DNS_SETTINGS")
            : new Intent(Settings.ACTION_WIRELESS_SETTINGS);
        if (intent.resolveActivity(getContext().getPackageManager()) == null) {
            intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
        }
        launchSettings(call, intent);
    }

    // ---------- Apps ----------

    @PluginMethod
    public void getInstalledApps(PluginCall call) {
        // Rasterizing + PNG + base64 for every launcher icon is heavy, so run off
        // Capacitor's single plugin handler thread and cache encoded icons.
        ioExecutor.execute(() -> {
            PackageManager pm = getContext().getPackageManager();
            List<ResolveInfo> activities = LaunchableAppCatalog.query(pm);

            Set<String> seen = new HashSet<>();
            JSArray apps = new JSArray();
            String self = getContext().getPackageName();
            DevicePolicyManager policy = (DevicePolicyManager) getContext().getSystemService(Context.DEVICE_POLICY_SERVICE);

            for (ResolveInfo ri : activities) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(self)
                    || !LaunchableAppCatalog.isVisiblePackage(pkg)
                    || !seen.add(pkg)) continue;

                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    boolean isSystem = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;

                    String installer = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        InstallSourceInfo src = pm.getInstallSourceInfo(pkg);
                        installer = src.getInstallingPackageName();
                        if (installer == null) installer = src.getInitiatingPackageName();
                    } else {
                        installer = pm.getInstallerPackageName(pkg);
                    }

                    JSObject app = new JSObject();
                    app.put("packageName", pkg);
                    app.put("label", pm.getApplicationLabel(ai).toString());
                    app.put("isSystem", isSystem);
                    app.put("installer", installer);
                    app.put("lockTaskPermitted", isLockTaskPermitted(policy, pkg));
                    app.put("icon", encodedIcon(pm, pkg, ai));
                    apps.put(app);
                } catch (Exception ignored) {}
            }

            JSObject ret = new JSObject();
            ret.put("apps", apps);
            call.resolve(ret);
        });
    }

    // Cache the encoded icon per package + versionCode; a reinstall/update bumps
    // the version code and invalidates the stale entry automatically.
    private String encodedIcon(PackageManager pm, String pkg, ApplicationInfo ai) {
        String cacheKey = pkg + "@" + packageVersionCode(pm, pkg);
        String cached = iconCache.get(cacheKey);
        if (cached != null) return cached;
        String encoded = drawableToBase64(pm.getApplicationIcon(ai));
        if (encoded != null) iconCache.put(cacheKey, encoded);
        return encoded;
    }

    private long packageVersionCode(PackageManager pm, String pkg) {
        try {
            PackageInfo info = pm.getPackageInfo(pkg, 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private boolean isLockTaskPermitted(DevicePolicyManager policy, String packageName) {
        if (policy == null) return false;
        try {
            // ArborXR is the Device Owner and maintains Android's lock-task
            // package allowlist. Installer provenance is not reliable here:
            // managed APKs commonly report a null installing package.
            return policy.isLockTaskPermitted(packageName);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String drawableToBase64(Drawable drawable) {
        try {
            int size = 144;
            Bitmap bitmap;
            if (drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null) {
                bitmap = Bitmap.createScaledBitmap(((BitmapDrawable) drawable).getBitmap(), size, size, true);
            } else {
                bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, size, size);
                drawable.draw(canvas);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    @PluginMethod
    public void launchApp(PluginCall call) {
        String pkg = call.getString("packageName");
        if (pkg == null) {
            call.reject("packageName is required");
            return;
        }
        Intent intent = getContext().getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) {
            call.reject("App not launchable: " + pkg);
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int lockState = lockTaskState();
        Log.i(LOG_TAG, "External launch requested package=" + pkg
            + " lockTask=" + lockTaskModeName(lockState));
        try {
            // In Device-Owner LOCKED mode Android only starts allowlisted
            // packages (everything else is rejected with a lock-task
            // violation), so merge the target into the allowlist first; the
            // kiosk stays engaged while the launched app runs.
            if (lockState == ActivityManager.LOCK_TASK_MODE_LOCKED) {
                KioskLock.applyDeviceOwnerLockdown(getContext(), Collections.singletonList(pkg));
            }
            unpinIfPinned();
            getContext().startActivity(intent);
            Log.i(LOG_TAG, "External launch accepted package=" + pkg);
            call.resolve();
        } catch (RuntimeException error) {
            Log.e(LOG_TAG, "External launch failed package=" + pkg, error);
            call.reject("Could not launch " + pkg + ": " + error.getMessage(),
                "APP_LAUNCH_FAILED", error);
        }
    }

    // ---------- Battery ----------

    @PluginMethod
    public void getBatteryInfo(PluginCall call) {
        Intent batteryStatus = getContext().registerReceiver(
            null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        call.resolve(readBattery(batteryStatus));
    }

    // Shared by getBatteryInfo (pull) and the batteryChanged event (push) so
    // both report level/charging identically.
    private static JSObject readBattery(Intent batteryStatus) {
        JSObject ret = new JSObject();
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
            ret.put("level", scale > 0 ? Math.round(level * 100f / scale) : -1);
            ret.put("isCharging", charging);
        } else {
            ret.put("level", -1);
            ret.put("isCharging", false);
        }
        return ret;
    }

    // ---------- Wi-Fi ----------

    private boolean hasWifiPermissions() {
        boolean fine = getPermissionState("location") == PermissionState.GRANTED;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return fine || getPermissionState("wifi") == PermissionState.GRANTED;
        }
        return fine;
    }

    @PluginMethod
    public void getWifiStatus(PluginCall call) {
        WifiManager wifi = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        boolean connected = false;
        Network active = cm.getActiveNetwork();
        if (active != null) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            connected = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }

        JSObject ret = new JSObject();
        ret.put("enabled", wifi.isWifiEnabled());
        ret.put("connected", connected);

        if (connected) {
            WifiInfo info = wifi.getConnectionInfo();
            if (info != null) {
                String ssid = info.getSSID();
                if (ssid != null) ssid = ssid.replace("\"", "");
                if ("<unknown ssid>".equals(ssid)) ssid = null;
                ret.put("ssid", ssid);
                ret.put("signal", WifiManager.calculateSignalLevel(info.getRssi(), 100));
            }
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void scanWifi(PluginCall call) {
        if (!hasWifiPermissions()) {
            requestPermissionForAlias("location", call, "wifiPermsCallback");
            return;
        }
        doWifiScan(call);
    }

    @PermissionCallback
    private void wifiPermsCallback(PluginCall call) {
        if (hasWifiPermissions()) {
            doWifiScan(call);
        } else {
            call.reject("Location permission is required to scan Wi-Fi networks", "PERMISSION_DENIED");
        }
    }

    @SuppressWarnings("deprecation")
    private void doWifiScan(PluginCall call) {
        WifiManager wifi = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (!wifi.isWifiEnabled()) {
            call.reject("Wi-Fi is disabled", "WIFI_DISABLED");
            return;
        }

        wifi.startScan(); // results may be from cache if throttled; still real data

        String currentSsid = null;
        WifiInfo info = wifi.getConnectionInfo();
        if (info != null && info.getSSID() != null) {
            currentSsid = info.getSSID().replace("\"", "");
        }

        Set<String> seen = new HashSet<>();
        JSArray networks = new JSArray();
        List<ScanResult> results;
        try {
            results = wifi.getScanResults();
        } catch (SecurityException e) {
            call.reject("Missing permission for scan results", "PERMISSION_DENIED");
            return;
        }

        for (ScanResult r : results) {
            String ssid = r.SSID;
            if (ssid == null || ssid.isEmpty() || !seen.add(ssid)) continue;
            JSObject n = new JSObject();
            n.put("ssid", ssid);
            n.put("signal", WifiManager.calculateSignalLevel(r.level, 100));
            String caps = r.capabilities != null ? r.capabilities : "";
            n.put("secured", caps.contains("WPA") || caps.contains("WEP") || caps.contains("EAP") || caps.contains("SAE"));
            n.put("connected", ssid.equals(currentSsid));
            networks.put(n);
        }

        JSObject ret = new JSObject();
        ret.put("networks", networks);
        call.resolve(ret);
    }

    @PluginMethod
    public void connectWifi(PluginCall call) {
        String ssid = call.getString("ssid");
        String password = call.getString("password", "");
        if (ssid == null) {
            call.reject("ssid is required");
            return;
        }

        // Fire OS does not allow a regular, non-device-owner kiosk app to save
        // a device-wide Wi-Fi configuration. WifiNetworkSuggestion leaves a
        // misleading "Available via OpenPanel" entry and can become stuck after
        // authentication fails. Delegate Fire tablets to the system picker so
        // Fire OS owns the saved network and credential.
        if (LandscapeOrientationLock.isFireDevice(Build.MANUFACTURER, Build.BRAND)) {
            launchWifiSettings();
            JSObject ret = new JSObject();
            ret.put("status", "system-settings");
            ret.put("code", 0);
            call.resolve(ret);
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            connectLegacyWifi(call, ssid, password);
            return;
        }
        connectSuggestedWifi(call, ssid, password);
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private void connectSuggestedWifi(PluginCall call, String ssid, String password) {
        WifiManager wifi = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder().setSsid(ssid);
        if (password != null && !password.isEmpty()) {
            builder.setWpa2Passphrase(password);
        }
        List<WifiNetworkSuggestion> suggestions = new ArrayList<>();
        suggestions.add(builder.build());

        // Add first; only clear the app's existing suggestion if Android reports
        // this SSID is already suggested (e.g. reconnecting with a corrected
        // password). Removing up-front would drop a working credential even when
        // the new add later fails for an unrelated reason.
        int status = wifi.addNetworkSuggestions(suggestions);
        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE) {
            wifi.removeNetworkSuggestions(new ArrayList<>());
            status = wifi.addNetworkSuggestions(suggestions);
        }

        JSObject ret = new JSObject();
        ret.put("status", status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ? "suggested" : "error");
        ret.put("code", status);
        call.resolve(ret);
    }

    @SuppressWarnings("deprecation")
    private void connectLegacyWifi(PluginCall call, String ssid, String password) {
        WifiManager wifi = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = quoteWifiValue(ssid);
        if (password == null || password.isEmpty()) {
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            configuration.preSharedKey = quoteWifiValue(password);
        }

        int networkId = wifi.addNetwork(configuration);
        if (networkId < 0 || !wifi.enableNetwork(networkId, true)) {
            call.reject("Android rejected the network request", "WIFI_REJECTED");
            return;
        }
        wifi.reconnect();
        JSObject ret = new JSObject();
        ret.put("status", "suggested");
        ret.put("code", 0); // STATUS_NETWORK_SUGGESTIONS_SUCCESS; inlined for API 24-28.
        call.resolve(ret);
    }

    private String quoteWifiValue(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @PluginMethod
    public void forgetWifi(PluginCall call) {
        String ssid = call.getString("ssid");
        if (ssid == null) {
            call.reject("ssid is required");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            forgetLegacyWifi(call, ssid);
            return;
        }
        forgetSuggestedWifi(call);
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private void forgetSuggestedWifi(PluginCall call) {
        WifiManager wifi = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        // OpenPanel keeps only one active suggestion, so an empty list removes
        // every suggestion owned by this app without retaining a Wi-Fi secret.
        wifi.removeNetworkSuggestions(new ArrayList<>());
        call.resolve();
    }

    @SuppressWarnings("deprecation")
    private void forgetLegacyWifi(PluginCall call, String ssid) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            call.reject("Location permission is required to forget this Wi-Fi network", "PERMISSION_DENIED");
            return;
        }
        WifiManager wifi = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        List<WifiConfiguration> configured;
        try {
            configured = wifi.getConfiguredNetworks();
        } catch (SecurityException e) {
            call.reject("Location permission is required to forget this Wi-Fi network", "PERMISSION_DENIED");
            return;
        }
        if (configured != null) {
            String quotedSsid = quoteWifiValue(ssid);
            for (WifiConfiguration configuration : configured) {
                if (quotedSsid.equals(configuration.SSID)) {
                    wifi.removeNetwork(configuration.networkId);
                    wifi.saveConfiguration();
                }
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void openWifiSettings(PluginCall call) {
        launchWifiSettings();
        call.resolve();
    }

    private void launchWifiSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            intent = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
        } else {
            intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
        }
        unpinIfPinned();
        getActivity().startActivity(intent);
    }

    // ---------- Bluetooth ----------

    private BluetoothAdapter getBtAdapter() {
        BluetoothManager bm = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    private boolean hasBtPermissions() {
        return BluetoothAccess.canReadAdapterState(
                Build.VERSION.SDK_INT,
                getPermissionState("bluetooth") == PermissionState.GRANTED);
    }

    private boolean isDeviceConnected(BluetoothDevice device) {
        try {
            Method m = device.getClass().getMethod("isConnected");
            return (boolean) m.invoke(device);
        } catch (Exception e) {
            return false;
        }
    }

    @PluginMethod
    public void getBluetoothStatus(PluginCall call) {
        BluetoothAdapter adapter = getBtAdapter();
        JSObject ret = new JSObject();
        if (adapter == null) {
            ret.put("available", false);
            ret.put("enabled", false);
            ret.put("permissionGranted", true);
            call.resolve(ret);
            return;
        }
        ret.put("available", true);
        JSArray devices = new JSArray();
        boolean permissionGranted = hasBtPermissions();
        ret.put("permissionGranted", permissionGranted);
        if (!permissionGranted) {
            ret.put("enabled", false);
            ret.put("devices", devices);
            call.resolve(ret);
            return;
        }

        boolean enabled;
        try {
            enabled = adapter.isEnabled();
        } catch (SecurityException e) {
            ret.put("permissionGranted", false);
            ret.put("enabled", false);
            ret.put("devices", devices);
            call.resolve(ret);
            return;
        }
        ret.put("enabled", enabled);

        if (enabled) {
            try {
                for (BluetoothDevice d : adapter.getBondedDevices()) {
                    JSObject dev = new JSObject();
                    dev.put("name", d.getName() != null ? d.getName() : d.getAddress());
                    dev.put("address", d.getAddress());
                    dev.put("paired", true);
                    dev.put("connected", isDeviceConnected(d));
                    devices.put(dev);
                }
            } catch (SecurityException ignored) {}
        }
        ret.put("devices", devices);
        call.resolve(ret);
    }

    @PluginMethod
    public void scanBluetooth(PluginCall call) {
        if (!hasBtPermissions()) {
            requestPermissionForAlias("bluetooth", call, "btPermsCallback");
            return;
        }
        doBtScan(call);
    }

    @PermissionCallback
    private void btPermsCallback(PluginCall call) {
        if (hasBtPermissions()) {
            doBtScan(call);
        } else {
            call.reject("Bluetooth permission is required to scan for devices", "PERMISSION_DENIED");
        }
    }

    private void doBtScan(PluginCall call) {
        BluetoothAdapter adapter = getBtAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            call.reject("Bluetooth is disabled", "BT_DISABLED");
            return;
        }

        // Settle any scan still in flight (resolves it with what it found) so its
        // kept-alive call is never orphaned by this new scan.
        finishBtScan();

        synchronized (btScanResults) {
            btScanResults.clear();
        }
        // Seed with bonded devices
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                JSObject dev = new JSObject();
                dev.put("name", d.getName() != null ? d.getName() : d.getAddress());
                dev.put("address", d.getAddress());
                dev.put("paired", true);
                dev.put("connected", isDeviceConnected(d));
                synchronized (btScanResults) {
                    btScanResults.add(dev);
                }
            }
        } catch (SecurityException ignored) {}

        if (btScanReceiver != null) {
            try { getContext().unregisterReceiver(btScanReceiver); } catch (Exception ignored) {}
            btScanReceiver = null;
        }

        call.setKeepAlive(true);
        synchronized (btScanLock) {
            btScanCall = call;
        }
        final Set<String> seen = new HashSet<>();
        synchronized (btScanResults) {
            for (JSObject d : btScanResults) seen.add(d.getString("address"));
        }

        btScanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null || d.getAddress() == null || !seen.add(d.getAddress())) return;
                    try {
                        JSObject dev = new JSObject();
                        String name = d.getName();
                        dev.put("name", name != null ? name : "Unknown (" + d.getAddress() + ")");
                        dev.put("address", d.getAddress());
                        dev.put("paired", d.getBondState() == BluetoothDevice.BOND_BONDED);
                        dev.put("connected", false);
                        dev.put("unnamed", name == null);
                        synchronized (btScanResults) {
                            btScanResults.add(dev);
                        }
                    } catch (SecurityException ignored) {}
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    finishBtScan();
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        getContext().registerReceiver(btScanReceiver, filter);

        boolean started;
        try {
            started = adapter.startDiscovery();
        } catch (SecurityException e) {
            started = false;
        }
        if (started) {
            // Watchdog: discovery normally emits ACTION_DISCOVERY_FINISHED in
            // ~12s, but if the adapter is turned off mid-scan (or the broadcast
            // is otherwise missed) that never arrives. Settle the call anyway so
            // the UI's "scanning" state can't get stuck on.
            synchronized (btScanLock) {
                btScanTimeout = new Runnable() {
                    @Override public void run() { finishBtScan(); }
                };
                mainHandler.postDelayed(btScanTimeout, BT_SCAN_TIMEOUT_MS);
            }
        } else {
            finishBtScan();
        }
    }

    private void unregisterBondReceiver() {
        if (bondReceiver != null) {
            try { getContext().unregisterReceiver(bondReceiver); } catch (Exception ignored) {}
            bondReceiver = null;
        }
    }

    // Settles the kept-alive pair call exactly once (reject). No-op when none is
    // pending. Mirrors how finishBtScan settles the scan call. Main-thread only.
    private void settlePendingPairCall(String message, String code) {
        PluginCall call = pendingPairCall;
        pendingPairCall = null;
        if (call == null) return;
        call.setKeepAlive(false);
        call.reject(message, code);
    }

    // Settles the in-flight scan exactly once. Safe to call from the discovery
    // broadcast, the watchdog, adapter-off, a new scan, or teardown; extra calls
    // after the first are no-ops because btScanCall is cleared atomically.
    private void finishBtScan() {
        PluginCall call;
        synchronized (btScanLock) {
            call = btScanCall;
            btScanCall = null;
            if (btScanTimeout != null) {
                mainHandler.removeCallbacks(btScanTimeout);
                btScanTimeout = null;
            }
        }
        if (btScanReceiver != null) {
            try { getContext().unregisterReceiver(btScanReceiver); } catch (Exception ignored) {}
            btScanReceiver = null;
        }
        if (call == null) return;
        JSArray devices = new JSArray();
        synchronized (btScanResults) {
            for (JSObject d : btScanResults) devices.put(d);
        }
        JSObject ret = new JSObject();
        ret.put("devices", devices);
        call.setKeepAlive(false);
        call.resolve(ret);
    }

    @PluginMethod
    public void pairBluetooth(PluginCall call) {
        String address = call.getString("address");
        if (address == null) {
            call.reject("address is required");
            return;
        }
        BluetoothAdapter adapter = getBtAdapter();
        if (adapter == null) {
            call.reject("Bluetooth unavailable");
            return;
        }
        if (!hasBtPermissions()) {
            call.reject("Bluetooth permission not granted", "PERMISSION_DENIED");
            return;
        }
        if (!adapter.isEnabled()) {
            call.reject("Bluetooth is disabled", "BT_DISABLED");
            return;
        }

        try {
            adapter.cancelDiscovery();
            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                JSObject ret = new JSObject();
                ret.put("status", "already-paired");
                call.resolve(ret);
                return;
            }

            // Only one pairing can be in flight. Settle any previously kept-alive
            // pair call before starting a new one (otherwise its JS Promise leaks)
            // and drop its receiver so handleOnDestroy can always clean up. The
            // terminal BONDED/NONE broadcast may never arrive if the plugin is torn
            // down while the system pairing dialog is still open.
            if (bondReceiver != null) {
                try { getContext().unregisterReceiver(bondReceiver); } catch (Exception ignored) {}
                bondReceiver = null;
            }
            settlePendingPairCall("Superseded by a newer pairing request", "PAIR_SUPERSEDED");

            call.setKeepAlive(true);
            pendingPairCall = call;
            bondReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null || !address.equals(d.getAddress())) return;
                    int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                    if (state == BluetoothDevice.BOND_BONDED) {
                        unregisterBondReceiver();
                        pendingPairCall = null;
                        JSObject ret = new JSObject();
                        ret.put("status", "paired");
                        call.setKeepAlive(false);
                        call.resolve(ret);
                    } else if (state == BluetoothDevice.BOND_NONE) {
                        unregisterBondReceiver();
                        pendingPairCall = null;
                        call.setKeepAlive(false);
                        call.reject("Pairing failed or was cancelled", "PAIR_FAILED");
                    }
                }
            };
            getContext().registerReceiver(bondReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));

            if (!device.createBond()) {
                unregisterBondReceiver();
                pendingPairCall = null;
                call.setKeepAlive(false);
                call.reject("Could not start pairing", "PAIR_FAILED");
            }
        } catch (SecurityException e) {
            unregisterBondReceiver();
            pendingPairCall = null;
            call.setKeepAlive(false);
            call.reject("Bluetooth permission not granted", "PERMISSION_DENIED");
        }
    }

    @PluginMethod
    public void unpairBluetooth(PluginCall call) {
        String address = call.getString("address");
        if (address == null) {
            call.reject("address is required");
            return;
        }
        BluetoothAdapter adapter = getBtAdapter();
        if (adapter == null) {
            call.reject("Bluetooth unavailable");
            return;
        }
        if (!hasBtPermissions()) {
            call.reject("Bluetooth permission not granted", "PERMISSION_DENIED");
            return;
        }
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            Method m = device.getClass().getMethod("removeBond");
            boolean ok = (boolean) m.invoke(device);
            JSObject ret = new JSObject();
            ret.put("status", ok ? "unpaired" : "failed");
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to unpair: " + e.getMessage());
        }
    }

    @PluginMethod
    public void setBluetoothEnabled(PluginCall call) {
        Boolean enabled = call.getBoolean("enabled", true);
        BluetoothAdapter adapter = getBtAdapter();
        if (adapter == null) {
            call.reject("Bluetooth unavailable");
            return;
        }
        if (!hasBtPermissions()) {
            requestPermissionForAlias("bluetooth", call, "btTogglePermsCallback");
            return;
        }
        doToggleBluetooth(call, enabled);
    }

    @PermissionCallback
    private void btTogglePermsCallback(PluginCall call) {
        if (hasBtPermissions()) {
            doToggleBluetooth(call, call.getBoolean("enabled", true));
        } else {
            call.reject("Bluetooth permission is required", "PERMISSION_DENIED");
        }
    }

    private void doToggleBluetooth(PluginCall call, boolean enabled) {
        // Keep the framework permission check inline: Android lint recognizes
        // this guard and the runtime still handles a permission revoked in-flight.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            call.reject("Bluetooth permission is required", "PERMISSION_DENIED");
            return;
        }
        BluetoothAdapter adapter = getBtAdapter();
        if (adapter == null) {
            call.reject("Bluetooth unavailable");
            return;
        }
        try {
            if (enabled == adapter.isEnabled()) {
                call.resolve();
                return;
            }
        } catch (SecurityException e) {
            call.reject("Bluetooth permission is required", "PERMISSION_DENIED");
            return;
        }
        // Apps targeting API 33+ cannot flip Bluetooth silently; both directions
        // go through a system confirmation dialog.
        String action = enabled
            ? BluetoothAdapter.ACTION_REQUEST_ENABLE
            : "android.bluetooth.adapter.action.REQUEST_DISABLE";
        unpinIfPinned();
        try {
            getActivity().startActivity(new Intent(action));
            call.resolve();
        } catch (Exception e) {
            // Some OEM builds block REQUEST_DISABLE — fall back to settings.
            Intent settings = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            getActivity().startActivity(settings);
            call.resolve();
        }
    }

    @PluginMethod
    public void openBluetoothSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        unpinIfPinned();
        getActivity().startActivity(intent);
        call.resolve();
    }

    @PluginMethod
    public void openSystemSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int lockState = lockTaskState();
        Log.i(LOG_TAG, "Full Android settings requested lockTask="
            + lockTaskModeName(lockState));
        try {
            unpinIfPinned();
            getActivity().startActivity(intent);
            Log.i(LOG_TAG, "Full Android settings launch accepted");
            call.resolve();
        } catch (RuntimeException error) {
            Log.e(LOG_TAG, "Full Android settings launch failed", error);
            call.reject("Could not open full Android settings: " + error.getMessage(),
                "SYSTEM_SETTINGS_FAILED", error);
        }
    }

    // ---------- Kiosk lock (standalone / non-ArborXR mode) ----------

    private DevicePolicyManager dpm() {
        return (DevicePolicyManager) getContext().getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    private boolean isDefaultLauncher() {
        return LauncherState.isDefaultLauncher(
            getContext().getPackageName(),
            resolvedHomePackage()
        );
    }

    private String homeControlMode() {
        return LauncherState.homeControlMode(
            getContext().getPackageName(),
            resolvedHomePackage(),
            KioskState.getMode(getContext()),
            isOpenPanelHomeEnabled(),
            DeviceAccess.isAccessibilityServiceEnabled(getContext())
        );
    }

    private boolean isOpenPanelHomeEnabled() {
        return DeviceAccess.isOpenPanelHomeEnabled(getContext());
    }

    private int lockTaskState() {
        ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        return am != null ? am.getLockTaskModeState() : ActivityManager.LOCK_TASK_MODE_NONE;
    }

    private String lockTaskModeName(int state) {
        if (state == ActivityManager.LOCK_TASK_MODE_LOCKED) return "locked";
        if (state == ActivityManager.LOCK_TASK_MODE_PINNED) return "pinned";
        return "none";
    }

    // MainActivity auto-pins (plain screen pinning, no Device Owner needed)
    // whenever it's the foreground screen, to block the gesture-nav app dock
    // and Overview/Recents. Plain pinning (unlike the DPM-allowlisted "locked"
    // mode from enableKioskLock) blocks switching to any other task, so any
    // method here that deliberately leaves OpenPanel's task must unpin first;
    // MainActivity re-pins on its own the next time it regains focus. Locked
    // mode is left alone — Android already permits switching among the
    // Device-Owner-allowlisted packages without unpinning.
    private void unpinIfPinned() {
        if (lockTaskState() == ActivityManager.LOCK_TASK_MODE_PINNED) {
            try { getActivity().stopLockTask(); } catch (Exception ignored) {}
        }
    }

    private ComponentName openPanelHomeAlias() {
        return new ComponentName(getContext().getPackageName(), HOME_ALIAS_CLASS);
    }

    private void setOpenPanelHomeEnabled(boolean enabled) {
        getContext().getPackageManager().setComponentEnabledSetting(
            openPanelHomeAlias(),
            enabled
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        );
    }

    private ComponentName findSystemHomeComponent() {
        PackageManager packageManager = getContext().getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> candidates = packageManager.queryIntentActivities(home, 0);
        ComponentName fallback = null;

        for (ResolveInfo candidate : candidates) {
            if (candidate.activityInfo == null) continue;
            String packageName = candidate.activityInfo.packageName;
            if (getContext().getPackageName().equals(packageName)) continue;

            ComponentName component = new ComponentName(
                packageName, candidate.activityInfo.name);
            if (!"com.android.settings".equals(packageName)) {
                if ((candidate.activityInfo.applicationInfo.flags
                        & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    return component;
                }
                if (fallback == null) fallback = component;
            }
        }
        return fallback;
    }

    @PluginMethod
    public void getKioskStatus(PluginCall call) {
        DevicePolicyManager dpm = dpm();
        String pkg = getContext().getPackageName();
        boolean deviceOwner = dpm != null && dpm.isDeviceOwnerApp(pkg);
        boolean deviceAdmin = dpm != null
                && dpm.isAdminActive(OpenPanelDeviceAdminReceiver.getComponentName(getContext()));
        // ArborXR manages the device when its MDM client is the Device Owner.
        boolean arborXrManaged = dpm != null && dpm.isDeviceOwnerApp(ARBORXR_DPC);
        int state = lockTaskState();

        JSObject ret = new JSObject();
        ret.put("deviceOwner", deviceOwner);
        ret.put("deviceAdmin", deviceAdmin);
        ret.put("arborXrManaged", arborXrManaged);
        ret.put("defaultLauncher", isDefaultLauncher());
        ret.put("homeControlMode", homeControlMode());
        ret.put("lockTaskActive", state != ActivityManager.LOCK_TASK_MODE_NONE);
        ret.put("lockTaskMode", lockTaskModeName(state));
        ret.put("managementMode", KioskState.getMode(getContext()));
        ret.put("kioskEnabled", KioskState.isEnabled(getContext()));
        call.resolve(ret);
    }

    @PluginMethod
    public void setManagementMode(PluginCall call) {
        String requestedMode = call.getString("mode", KioskState.MODE_COMPANION);
        boolean fireDevice = LandscapeOrientationLock.isFireDevice(
            Build.MANUFACTURER,
            Build.BRAND
        );
        String mode = KioskState.normalizeModeForDevice(requestedMode, fireDevice);
        boolean wasEnabled = KioskState.isEnabled(getContext());
        KioskState.setMode(getContext(), mode);

        if (KioskState.MODE_COMPANION.equals(mode)) {
            KioskState.setEnabled(getContext(), false);
            if (wasEnabled) {
                getActivity().runOnUiThread(() -> {
                    try {
                        if (lockTaskState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                            getActivity().stopLockTask();
                        }
                    } catch (Exception ignored) {}
                });
            }
        }

        JSObject ret = new JSObject();
        ret.put("mode", mode);
        call.resolve(ret);
    }

    @PluginMethod
    public void requestDeviceAdmin(PluginCall call) {
        DevicePolicyManager dpm = dpm();
        ComponentName admin = OpenPanelDeviceAdminReceiver.getComponentName(getContext());
        if (dpm != null && dpm.isAdminActive(admin)) {
            JSObject ret = new JSObject();
            ret.put("status", "already-admin");
            call.resolve(ret);
            return;
        }
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Enable so OpenPanel can lock this device into kiosk mode.");
        // The system DeviceAdminAdd screen refuses to launch as a new task
        // ("Cannot start ADD_DEVICE_ADMIN as a new task"), so start it in the
        // launcher's own task (no FLAG_ACTIVITY_NEW_TASK).
        unpinIfPinned();
        try {
            getActivity().startActivity(intent);
            call.resolve();
        } catch (RuntimeException error) {
            call.reject("Could not open the device-admin prompt: " + error.getMessage(),
                    "DEVICE_ADMIN_FAILED", error);
        }
    }

    @PluginMethod
    public void openLauncherSettings(PluginCall call) {
        // Exit Kiosk disables the HOME alias. Re-enable it before presenting
        // the chooser so an operator can deliberately select OpenPanel again.
        setOpenPanelHomeEnabled(true);
        Intent intent = new Intent(Settings.ACTION_HOME_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        unpinIfPinned();
        try {
            getActivity().startActivity(intent);
        } catch (Exception e) {
            // Some OEM builds lack the Home settings screen — fall back to Settings.
            getActivity().startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        call.resolve();
    }

    // ---------- Native admin authorization for kiosk-dismantling actions ----------

    /**
     * Mirror the web layer's admin PIN verifier into native SharedPreferences so
     * the kiosk-dismantling bridge methods can be gated natively. See
     * requireKioskAdmin() for the contract. The web layer calls this with
     * { verifier: <the same salted, non-reversible hash it stores in localStorage> }
     * on PIN create/change, and clears it (empty verifier) when the PIN is removed.
     * The raw PIN is never passed here.
     */
    @PluginMethod
    public void setKioskAdminVerifier(PluginCall call) {
        String verifier = call.getString("verifier", "");
        SharedPreferences.Editor editor = deviceHealthPrefs().edit();
        if (verifier == null || verifier.isEmpty()) {
            editor.remove(PREF_KIOSK_ADMIN_VERIFIER);
        } else {
            editor.putString(PREF_KIOSK_ADMIN_VERIFIER, verifier);
        }
        editor.apply();
        call.resolve();
    }

    /**
     * Native authorization gate for the kiosk-dismantling bridge methods
     * (disableKioskLock, exitKioskToSystemHome, clearDeviceOwner) and the
     * caller-supplied lock-task allowlist in enableKioskLock.
     *
     * The admin PIN itself lives in the WebView's localStorage (a salted hash),
     * which native code cannot read, so any script running in the WebView could
     * otherwise call these methods directly and dismantle the kiosk. This guard
     * requires the caller to prove knowledge of an admin verifier that the web
     * layer mirrors into native prefs via setKioskAdminVerifier().
     *
     * Assumed contract (documented for the web layer):
     *   - On PIN create/change: SystemBridge.setKioskAdminVerifier({ verifier }).
     *   - Each gated call passes { adminToken: &lt;that same verifier value&gt; }.
     *
     * Migration safety: until a verifier is provisioned the gate fails OPEN (logs
     * a warning) so existing installs and the documented Device-Owner escape hatch
     * keep working; once provisioned it fails CLOSED. This is scaffolding, not a
     * bypass — no PIN or token is hardcoded, and once provisioned these methods
     * cannot be driven without the verifier.
     */
    private boolean requireKioskAdmin(PluginCall call, String methodName) {
        String verifier = deviceHealthPrefs().getString(PREF_KIOSK_ADMIN_VERIFIER, null);
        if (verifier == null || verifier.isEmpty()) {
            Log.w(LOG_TAG, "Kiosk admin gate unprovisioned; allowing " + methodName
                + " (web layer has not called setKioskAdminVerifier)");
            return true;
        }
        if (constantTimeEquals(verifier, call.getString("adminToken", ""))) return true;
        Log.w(LOG_TAG, "Kiosk admin gate rejected " + methodName + " (missing/invalid adminToken)");
        call.reject("Admin authorization required", "ADMIN_AUTH_REQUIRED");
        return false;
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) return false;
        if (expected.length() != provided.length()) return false;
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ provided.charAt(i);
        }
        return diff == 0;
    }

    @PluginMethod
    public void enableKioskLock(final PluginCall call) {
        if (!requireKioskAdmin(call, "enableKioskLock")) return;
        if (!KioskState.MODE_STANDALONE.equals(KioskState.getMode(getContext()))) {
            call.reject("OpenPanel only starts its own kiosk lock in standalone mode", "WRONG_MODE");
            return;
        }
        final DevicePolicyManager dpm = dpm();
        final String pkg = getContext().getPackageName();
        final boolean deviceOwner = dpm != null && dpm.isDeviceOwnerApp(pkg);
        final boolean fireDevice = LandscapeOrientationLock.isFireDevice(
            Build.MANUFACTURER, Build.BRAND);
        if (KioskState.shouldUseFireRedirectKiosk(deviceOwner, fireDevice)) {
            setOpenPanelHomeEnabled(true);
            if (!DeviceAccess.isAccessibilityServiceEnabled(getContext())) {
                KioskState.setEnabled(getContext(), false);
                call.reject(
                    "Enable OpenPanel's accessibility service before starting the Fire kiosk.",
                    "FIRE_KIOSK_NEEDS_ACCESSIBILITY"
                );
                return;
            }
            KioskState.setEnabled(getContext(), true);
            JSObject result = new JSObject();
            result.put("status", LauncherState.MODE_FIRE_REDIRECT);
            result.put("deviceOwner", false);
            result.put("allowlisted", false);
            call.resolve(result);
            return;
        }
        setOpenPanelHomeEnabled(true);

        // As Device Owner, allowlist OpenPanel + the caller-supplied apps and hide
        // the status bar/notifications so startLockTask() below enters the strong,
        // silent LOCKED kiosk (like Fully) and launched apps stay inside it.
        // Otherwise startLockTask() falls back to user-confirmed screen pinning.
        List<String> extra = new ArrayList<>();
        JSArray packages = call.getArray("packages");
        if (packages != null) {
            for (int i = 0; i < packages.length(); i++) {
                String p = packages.optString(i, null);
                if (p != null && !p.isEmpty()) extra.add(p);
            }
        }
        final boolean strongLock = KioskLock.applyDeviceOwnerLockdown(getContext(), extra);
        if (deviceOwner && !strongLock) {
            Log.w(LOG_TAG, "Device-owner lockdown failed; kiosk will be screen-pinning only");
        }

        KioskState.setEnabled(getContext(), true);
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    getActivity().startLockTask();
                    waitForKioskStart(call, deviceOwner, strongLock, 20);
                } catch (Exception e) {
                    KioskState.setEnabled(getContext(), false);
                    call.reject("Could not enter kiosk lock: " + e.getMessage(), "LOCK_FAILED");
                }
            }
        });
    }

    private void waitForKioskStart(
            final PluginCall call,
            final boolean deviceOwner,
            final boolean allowlisted,
            final int attemptsRemaining) {
        int state = lockTaskState();
        if (KioskState.isLockTaskActive(state)) {
            JSObject ret = new JSObject();
            ret.put("status", lockTaskModeName(state));
            ret.put("deviceOwner", deviceOwner);
            ret.put("allowlisted", allowlisted);
            call.resolve(ret);
            return;
        }
        if (attemptsRemaining <= 0) {
            KioskState.setEnabled(getContext(), false);
            call.reject(
                "Android did not enter screen pinning. Confirm the pinning prompt when shown.",
                "LOCK_NOT_ACTIVE");
            return;
        }
        mainHandler.postDelayed(
            () -> waitForKioskStart(call, deviceOwner, allowlisted, attemptsRemaining - 1),
            500L);
    }

    @PluginMethod
    public void disableKioskLock(final PluginCall call) {
        if (!requireKioskAdmin(call, "disableKioskLock")) return;
        KioskState.setEnabled(getContext(), false);
        // Restore the status bar / notification shade that enableKioskLock hid
        // as Device Owner, so exiting the kiosk returns a normal, usable device.
        KioskLock.releaseStatusBar(getContext());
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (lockTaskState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                        getActivity().stopLockTask();
                    }
                    call.resolve();
                } catch (Exception e) {
                    call.reject("Could not exit kiosk lock: " + e.getMessage(), "UNLOCK_FAILED");
                }
            }
        });
    }

    /**
     * Leave standalone kiosk mode and return directly to the device's built-in
     * Home screen. Device Owner is intentionally retained; relinquishing it is
     * irreversible and remains a separate authenticated admin action.
     */
    @PluginMethod
    public void exitKioskToSystemHome(final PluginCall call) {
        if (!requireKioskAdmin(call, "exitKioskToSystemHome")) return;
        KioskState.setEnabled(getContext(), false);
        KioskState.setKeyguardDisabled(getContext(), false);

        getActivity().runOnUiThread(() -> {
            DevicePolicyManager policy = dpm();
            String packageName = getContext().getPackageName();
            ComponentName systemHome = findSystemHomeComponent();

            try {
                if (lockTaskState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                    getActivity().stopLockTask();
                }

                if (policy != null && policy.isDeviceOwnerApp(packageName)) {
                    ComponentName admin =
                        OpenPanelDeviceAdminReceiver.getComponentName(getContext());
                    try { policy.setStatusBarDisabled(admin, false); }
                    catch (Exception error) {
                        Log.w(LOG_TAG, "Could not restore status bar during kiosk exit", error);
                    }
                    try { policy.setKeyguardDisabled(admin, false); }
                    catch (Exception error) {
                        Log.w(LOG_TAG, "Could not restore keyguard during kiosk exit", error);
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try {
                            policy.setLockTaskFeatures(
                                admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
                        } catch (Exception error) {
                            Log.w(LOG_TAG, "Could not clear lock-task features", error);
                        }
                    }
                    try { policy.setLockTaskPackages(admin, new String[0]); }
                    catch (Exception error) {
                        Log.w(LOG_TAG, "Could not clear lock-task packages", error);
                    }
                }

                // Removing only our HOME alias invalidates OpenPanel as the
                // launcher without disabling its ordinary app entry point.
                setOpenPanelHomeEnabled(false);

                Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                if (systemHome != null) home.setComponent(systemHome);

                JSObject result = new JSObject();
                result.put("exited", true);
                result.put("launcherPackage",
                    systemHome != null ? systemHome.getPackageName() : null);
                result.put("deviceOwnerRetained",
                    policy != null && policy.isDeviceOwnerApp(packageName));
                call.resolve(result);

                // Let Capacitor deliver the resolved Promise to the WebView
                // before Home backgrounds it. The UI does not depend on the
                // result, but callers and diagnostics should still settle.
                mainHandler.postDelayed(() -> {
                    try {
                        getActivity().startActivity(home);
                    } catch (Exception error) {
                        Log.e(LOG_TAG, "Could not launch system Home after kiosk exit", error);
                    }
                }, 150);
                Log.i(LOG_TAG, "Kiosk exit completed launcher="
                    + (systemHome != null ? systemHome.flattenToShortString() : "implicit")
                    + " lockTask=" + lockTaskModeName(lockTaskState()));
            } catch (Exception error) {
                Log.e(LOG_TAG, "Kiosk exit to system Home failed", error);
                call.reject("Could not return to the system launcher: "
                    + error.getMessage(), "EXIT_KIOSK_FAILED", error);
            }
        });
    }

    // Fully relinquish Device Owner so the operator can hand the tablet back to
    // normal management without a factory reset. Restores the status bar, ends
    // any lock task, and clears device ownership. Only the DO app itself can do
    // this, so it's the safe escape hatch from a provisioned kiosk.
    @PluginMethod
    public void clearDeviceOwner(final PluginCall call) {
        if (!requireKioskAdmin(call, "clearDeviceOwner")) return;
        final DevicePolicyManager dpm = dpm();
        final String pkg = getContext().getPackageName();
        if (dpm == null || !dpm.isDeviceOwnerApp(pkg)) {
            call.reject("OpenPanel is not the device owner", "NOT_DEVICE_OWNER");
            return;
        }
        KioskState.setEnabled(getContext(), false);
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    ComponentName adminName = OpenPanelDeviceAdminReceiver.getComponentName(getContext());
                    try { dpm.setStatusBarDisabled(adminName, false); } catch (Exception ignored) {}
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try { dpm.setLockTaskFeatures(adminName,
                                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS); } catch (Exception ignored) {}
                    }
                    try { dpm.setLockTaskPackages(adminName, new String[0]); } catch (Exception ignored) {}
                    if (lockTaskState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                        try { getActivity().stopLockTask(); } catch (Exception ignored) {}
                    }
                    dpm.clearDeviceOwnerApp(pkg);
                    JSObject ret = new JSObject();
                    ret.put("cleared", true);
                    call.resolve(ret);
                } catch (Exception e) {
                    call.reject("Could not clear device owner: " + e.getMessage(), "CLEAR_DO_FAILED");
                }
            }
        });
    }

    // ---------- Fully-style permissions & special app access ----------

    // One snapshot of every grant the kiosk cares about. The UI shows a row per
    // entry, auto-grants what a Device Owner can, and deep-links the rest.
    @PluginMethod
    public void getPermissionStatus(PluginCall call) {
        Context ctx = getContext();
        DevicePolicyManager dpm = dpm();
        String pkg = ctx.getPackageName();
        boolean deviceOwner = dpm != null && dpm.isDeviceOwnerApp(pkg);

        JSObject r = new JSObject();
        r.put("deviceOwner", deviceOwner);
        r.put("deviceAdmin", dpm != null
            && dpm.isAdminActive(OpenPanelDeviceAdminReceiver.getComponentName(ctx)));
        r.put("defaultLauncher", isDefaultLauncher());
        r.put("homeControlMode", homeControlMode());
        r.put("location", getPermissionState("location") == PermissionState.GRANTED);
        r.put("bluetooth", hasBtPermissions());
        r.put("accessibility", DeviceAccess.isAccessibilityServiceEnabled(ctx));
        r.put("notificationAccess", DeviceAccess.isNotificationListenerEnabled(ctx));
        r.put("overlay", DeviceAccess.canDrawOverlays(ctx));
        r.put("usageAccess", DeviceAccess.hasUsageAccess(ctx));
        r.put("writeSettings", DeviceAccess.canWriteSettings(ctx));
        r.put("batteryUnrestricted", DeviceAccess.isIgnoringBatteryOptimizations(ctx));
        r.put("keyguardDisabled", KioskState.isKeyguardDisabled(ctx));
        call.resolve(r);
    }

    // As Device Owner, silently grant OpenPanel's own dangerous runtime
    // permissions so onboarding never has to prompt for Wi-Fi scanning or
    // Bluetooth. No-op (deviceOwner:false) when not the owner — the UI then
    // falls back to the normal runtime prompts.
    @PluginMethod
    public void autoGrantSelfPermissions(PluginCall call) {
        DevicePolicyManager dpm = dpm();
        String pkg = getContext().getPackageName();
        JSObject r = new JSObject();
        if (dpm == null || !dpm.isDeviceOwnerApp(pkg)) {
            r.put("deviceOwner", false);
            r.put("granted", 0);
            call.resolve(r);
            return;
        }
        ComponentName admin = OpenPanelDeviceAdminReceiver.getComponentName(getContext());
        java.util.List<String> perms = new java.util.ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        int granted = 0;
        for (String p : perms) {
            try {
                if (dpm.setPermissionGrantState(admin, pkg, p,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)) {
                    granted++;
                }
            } catch (Exception ignored) {}
        }
        r.put("deviceOwner", true);
        r.put("granted", granted);
        call.resolve(r);
    }

    // Device-Owner only: hide (or restore) the swipe lock screen so a kiosk wakes
    // straight into OpenPanel. Remembered in KioskState and re-applied on launch.
    @PluginMethod
    public void setKeyguardDisabled(PluginCall call) {
        boolean disabled = Boolean.TRUE.equals(call.getBoolean("disabled", Boolean.TRUE));
        DevicePolicyManager dpm = dpm();
        if (dpm == null || !dpm.isDeviceOwnerApp(getContext().getPackageName())) {
            call.reject("OpenPanel is not the device owner", "NOT_DEVICE_OWNER");
            return;
        }
        boolean applied;
        try {
            applied = dpm.setKeyguardDisabled(
                OpenPanelDeviceAdminReceiver.getComponentName(getContext()), disabled);
        } catch (Exception e) {
            call.reject("Could not change the lock screen: " + e.getMessage(), "KEYGUARD_FAILED");
            return;
        }
        // setKeyguardDisabled refuses when a PIN/password is set; report the real
        // outcome so the UI doesn't claim success when the lock screen stays.
        KioskState.setKeyguardDisabled(getContext(), disabled && applied);
        JSObject r = new JSObject();
        r.put("disabled", disabled && applied);
        r.put("applied", applied);
        call.resolve(r);
    }

    @PluginMethod
    public void openAccessibilitySettings(PluginCall call) {
        launchSettings(call, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    @PluginMethod
    public void openNotificationListenerSettings(PluginCall call) {
        launchSettings(call, new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    @PluginMethod
    public void openOverlaySettings(PluginCall call) {
        launchSettings(call, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getContext().getPackageName())));
    }

    @PluginMethod
    public void openUsageAccessSettings(PluginCall call) {
        launchSettings(call, new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
    }

    @PluginMethod
    public void openWriteSettings(PluginCall call) {
        launchSettings(call, new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:" + getContext().getPackageName())));
    }

    @PluginMethod
    public void requestIgnoreBatteryOptimizations(PluginCall call) {
        if (DeviceAccess.isIgnoringBatteryOptimizations(getContext())) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
            return;
        }
        launchSettings(call, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + getContext().getPackageName())));
    }

    // Everything above deep-links a Settings screen. Both plain PINNED and
    // Device-Owner LOCKED lock tasks block launching a non-allowlisted Settings
    // activity, so leave the lock task first; MainActivity re-pins the moment
    // OpenPanel regains focus (so an operator-driven grant is a brief detour).
    private void launchSettings(final PluginCall call, final Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (lockTaskState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                        try { getActivity().stopLockTask(); } catch (Exception ignored) {}
                    }
                    getActivity().startActivity(intent);
                    call.resolve();
                } catch (RuntimeException error) {
                    call.reject("Could not open settings: " + error.getMessage(),
                        "SETTINGS_FAILED", error);
                }
            }
        });
    }

}
