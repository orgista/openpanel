package com.orgista.openpanel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Conservative, auditable package policy used by OpenPanel Device Health.
 *
 * <p>Rules are intentionally exact package names. OpenPanel never pattern-hides
 * unknown packages, launchers, device-policy clients, wallpaper providers, or
 * core Android services. Device Owner APIs hide these packages reversibly; they
 * do not modify the read-only system partition.</p>
 */
public final class DebloatCatalog {

    public static final class Rule {
        public final String packageName;
        public final String label;
        public final String category;
        public final String reason;
        public final String profile;

        Rule(String packageName, String label, String category, String reason, String profile) {
            this.packageName = packageName;
            this.label = label;
            this.category = category;
            this.reason = reason;
            this.profile = profile;
        }
    }

    private static final List<Rule> RULES = Collections.unmodifiableList(Arrays.asList(
        // Cross-vendor Google consumer apps commonly preloaded on managed tablets.
        rule("com.google.android.apps.books", "Google Play Books", "media", "Optional reading storefront", "generic"),
        rule("com.google.android.play.games", "Google Play Games", "games", "Optional games profile and promotions", "generic"),
        rule("com.google.android.youtube", "YouTube", "media", "OpenPanel provides its own restricted YouTube player", "generic"),
        rule("com.google.android.apps.youtube.kids", "YouTube Kids", "kids", "Optional consumer video app", "generic"),
        rule("com.google.android.youtube.kids", "YouTube Kids (legacy)", "kids", "Legacy package alias", "generic"),
        rule("com.google.android.apps.kids.home", "Google Kids Space", "kids", "Optional consumer kids launcher", "generic"),
        rule("com.google.android.keep", "Google Keep", "productivity", "Optional personal notes app", "generic"),
        rule("com.google.android.apps.walletnfcrel", "Google Wallet", "payments", "Optional personal payments app", "generic"),
        rule("com.google.android.apps.nbu.paisa.user", "Google Pay", "payments", "Optional personal payments app", "generic"),
        rule("com.google.android.apps.youtube.music", "YouTube Music", "media", "Optional consumer music app", "generic"),
        rule("com.google.android.feedback", "Google Feedback", "telemetry", "Optional feedback and diagnostics uploader", "generic"),
        rule("com.google.android.gms.location.history", "Google Location History", "telemetry", "Optional location-history service", "generic"),
        rule("com.google.android.tv.bugreportsender", "Android TV Bug Reports", "telemetry", "Optional TV bug-report uploader", "generic"),
        rule("com.google.android.tvrecommendations", "Google TV Recommendations", "promotions", "Consumer home-screen recommendations", "generic"),
        rule("com.google.android.leanbacklauncher.recommendations", "Android TV Recommendations", "promotions", "Legacy home-screen recommendations", "generic"),
        rule("com.google.android.youtube.tv", "YouTube for Android TV", "media", "OpenPanel provides its own restricted YouTube player", "generic"),
        rule("com.google.android.youtube.tvmusic", "YouTube Music for Android TV", "media", "Optional unrestricted music app", "generic"),

        // TCL Android / Google TV consumer extras. TV input, remote, Settings,
        // WebView, OTA, and both fallback launchers stay protected below.
        rule("com.tcl.appmarket2", "TCL App Store", "promotions", "Optional OEM app storefront", "tcl"),
        rule("com.tcl.bi", "TCL Usage Analytics", "telemetry", "Optional OEM usage analytics service", "tcl"),
        rule("com.tcl.bootadservice", "TCL Boot Ads", "promotions", "Boot advertising service", "tcl"),
        rule("com.tcl.esticker", "TCL E-Sticker", "promotions", "Retail and promotional overlay", "tcl"),
        rule("com.tcl.showmode", "TCL Show Mode", "promotions", "Retail demonstration mode", "tcl"),
        rule("com.tcl.usercenter", "TCL User Center", "promotions", "Optional consumer account hub", "tcl"),
        rule("com.tcl.waterfall.overseas", "TCL Content Waterfall", "promotions", "OEM content recommendation surface", "tcl"),

        // Lenovo / Motorola-family packages.
        rule("com.lenovo.hec.lenovoextend", "Lenovo FreeStyle", "oem", "Optional cross-device companion", "lenovo"),
        rule("com.lmsa.app.lmsapad", "Lenovo App Explorer", "promotions", "OEM app recommendations", "lenovo"),
        rule("com.motorola.demo", "Motorola Demo Mode", "oem", "Retail demonstration content", "motorola"),
        rule("com.motorola.motocare", "Moto Care", "support", "Optional consumer support app", "motorola"),
        rule("com.motorola.help", "Moto Help", "support", "Optional consumer help app", "motorola"),

        // Samsung consumer extras. Knox, setup, update, and core account packages are excluded.
        rule("com.samsung.android.app.spage", "Samsung Free", "promotions", "News and promotional home feed", "samsung"),
        rule("com.samsung.android.game.gamehome", "Samsung Game Launcher", "games", "Optional games hub", "samsung"),
        rule("com.samsung.android.app.tips", "Samsung Tips", "support", "Optional tips app", "samsung"),
        rule("com.samsung.android.arzone", "Samsung AR Zone", "media", "Optional AR effects", "samsung"),
        rule("com.samsung.android.aremoji", "Samsung AR Emoji", "media", "Optional AR avatar package", "samsung"),
        rule("com.samsung.android.aremojieditor", "Samsung AR Emoji Editor", "media", "Optional AR avatar editor", "samsung"),
        rule("com.facebook.appmanager", "Meta App Manager", "telemetry", "Background Meta app installer", "samsung"),
        rule("com.facebook.services", "Meta Services", "telemetry", "Background Meta services", "samsung"),
        rule("com.facebook.system", "Meta App Installer", "telemetry", "Background Meta installer", "samsung"),

        // Xiaomi / Redmi / Poco.
        rule("com.miui.msa.global", "MIUI System Ads", "telemetry", "Advertising service", "xiaomi"),
        rule("com.miui.analytics", "MIUI Analytics", "telemetry", "OEM analytics service", "xiaomi"),
        rule("com.xiaomi.mipicks", "GetApps", "promotions", "OEM app storefront and promotions", "xiaomi"),
        rule("com.mi.globalbrowser", "Mi Browser", "browser", "Optional OEM browser", "xiaomi"),
        rule("com.miui.video", "Mi Video", "media", "Optional OEM video app", "xiaomi"),
        rule("com.miui.yellowpage", "MIUI Yellow Pages", "promotions", "Optional business recommendations", "xiaomi"),

        // OnePlus / Oppo / Realme share much of the Oplus/HeyTap stack.
        rule("com.heytap.browser", "HeyTap Browser", "browser", "Optional OEM browser", "oplus"),
        rule("com.oplus.games", "Oplus Games", "games", "Optional games hub", "oplus"),
        rule("com.coloros.video", "ColorOS Video", "media", "Optional OEM video app", "oplus"),
        rule("com.coloros.weather2", "ColorOS Weather", "oem", "Optional weather app", "oplus"),
        rule("com.oppo.usercenter", "Oppo User Center", "promotions", "Optional consumer account hub", "oplus"),
        rule("com.realmecomm.app", "Realme Community", "promotions", "Community and promotional content", "oplus"),

        // Huawei / Honor.
        rule("com.huawei.browser", "Huawei Browser", "browser", "Optional OEM browser", "huawei"),
        rule("com.huawei.himovie.overseas", "Huawei Video", "media", "Optional OEM video app", "huawei"),
        rule("com.huawei.videoeditor", "Huawei Video Editor", "media", "Optional video editor", "huawei"),
        rule("com.huawei.tips", "Huawei Tips", "support", "Optional tips app", "huawei"),

        // Amazon Fire OS consumer apps. Core launcher, settings, Appstore, WebView,
        // OTA/setup, and the Amazon parental-control DPC stay protected below.
        // The extended entries mirror the conservative, reversible subset
        // validated on Fire OS 8; low-level identity, sync, security, and
        // connectivity packages are intentionally excluded.
        rule("com.amazon.afe.app", "Tap to Alexa", "assistant", "Optional Alexa touch interface", "amazon"),
        rule("com.amazon.avod", "Prime Video", "media", "Optional video streaming app", "amazon"),
        rule("com.amazon.cloud9.kids", "Amazon Kids Web Browser", "kids", "Optional kids browser", "amazon"),
        rule("com.amazon.comms.kids", "Alexa Communication for Kids", "kids", "Optional kids communication app", "amazon"),
        rule("com.amazon.dee.alexaonandroidos", "Alexa on Fire OS", "assistant", "Optional Alexa integration", "amazon"),
        rule("com.amazon.dee.app", "Alexa", "assistant", "Optional voice assistant app", "amazon"),
        rule("com.amazon.firespotlight", "Amazon Appstore Spotlight", "promotions", "App recommendations and promotions", "amazon"),
        rule("com.amazon.h2settingsfortablet", "Amazon Profiles", "kids", "Optional profiles and Family Library settings", "amazon"),
        rule("com.amazon.hedwig", "Fire TV Channels", "media", "Optional channel discovery app", "amazon"),
        rule("com.amazon.ods.kindleconnect", "Amazon Screen Sharing", "support", "Optional remote support screen sharing", "amazon"),
        rule("com.amazon.tablet.voiceassistant", "Alexa Voice Assistant", "assistant", "Optional Alexa voice service", "amazon"),
        rule("com.amazon.tahoe", "Amazon Kids+", "kids", "Optional kids content and launcher", "amazon"),
        rule("com.amazon.kindle", "Kindle", "media", "Optional reading app", "amazon"),
        rule("com.amazon.photos", "Amazon Photos", "media", "Optional photo backup app", "amazon"),
        rule("com.amazon.mp3", "Amazon Music", "media", "Optional music app", "amazon"),
        rule("com.amazon.weather", "Amazon Weather", "oem", "Optional weather app", "amazon"),
        rule("com.amazon.windowshop", "Amazon Shopping", "promotions", "Optional shopping app", "amazon"),
        rule("com.amazon.imdb.tv.mobile.app", "IMDb", "media", "Optional entertainment app", "amazon"),
        rule("com.amazon.advertisingidsettings", "Amazon Advertising ID", "telemetry", "Optional advertising identifier controls", "amazon"),
        rule("com.amazon.client.metrics", "Amazon Client Metrics", "telemetry", "Optional Amazon usage metrics service", "amazon"),
        rule("com.amazon.client.metrics.api", "Amazon Client Metrics API", "telemetry", "Optional Amazon usage metrics interface", "amazon"),
        rule("com.amazon.device.metrics", "Amazon Device Metrics", "telemetry", "Optional Amazon device metrics uploader", "amazon"),
        rule("com.amazon.dp.logger", "Amazon DP Logger", "telemetry", "Optional Amazon diagnostics logger", "amazon"),
        rule("com.amazon.hybridadidservice", "Amazon Hybrid Ad ID", "telemetry", "Optional advertising identifier service", "amazon"),
        rule("com.amazon.wirelessmetrics.service", "Amazon Wireless Metrics", "telemetry", "Optional wireless usage metrics", "amazon"),
        rule("com.audible.application.kindle", "Audible", "media", "Optional audiobook storefront", "amazon"),
        rule("com.goodreads.kindle", "Goodreads", "media", "Optional reading community app", "amazon"),
        rule("com.kingsoft.office.amz", "WPS Office for Amazon", "productivity", "Optional office suite", "amazon")
    ));

    private static final Set<String> PROTECTED_PACKAGES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.shell",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.bluetooth",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.providers.downloads",
            "com.android.cellbroadcastreceiver",
            "com.google.android.cellbroadcastreceiver",
            "com.android.emergency",
            "com.android.managedprovisioning",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.wallpaper.livepicker",
            "com.android.wallpaperbackup",
            "com.android.wallpapercropper",
            "com.amazon.application.compatibility.enforcer",
            "com.amazon.appverification",
            "com.amazon.device.software.ota",
            "com.amazon.device.software.ota.override",
            "com.amazon.firelauncher",
            "com.amazon.frameworksettings",
            "com.amazon.kindle.otter.oobe",
            "com.amazon.kindle.otter.oobe.forced.ota",
            "com.amazon.parentalcontrols",
            "com.amazon.pm",
            "com.amazon.settings",
            "com.amazon.settings.systemupdates",
            "com.amazon.venezia",
            "com.amazon.webview.chromium",
            "com.android.tv.settings",
            "com.google.android.leanbacklauncher",
            "com.google.android.tv.remote.service",
            "com.google.android.tvlauncher",
            "com.tcl.android.webview",
            "com.tcl.inputmethod.international",
            "com.tcl.keycustomfunctionservice",
            "com.tcl.rc.ota",
            "com.tcl.settings",
            "com.tcl.tcl_bt_rcu_service",
            "com.tcl.tv",
            "com.tcl.tvinput",
            "com.tcl.versionUpdateApp",
            "com.tblenovo.wallpaper",
            "com.zui.theme.settings",
            "com.zui.themes.provider",
            "com.lenovo.hecplatform.hecagent",
            "com.lenovo.ota",
            "app.xrdm.client",
            "app.xrdm.launcher",
            "com.orgista.openpanel",
            "com.orgista.openpanel.debug"
        ))
    );

    private DebloatCatalog() {}

    private static Rule rule(
        String packageName,
        String label,
        String category,
        String reason,
        String profile
    ) {
        return new Rule(packageName, label, category, reason, profile);
    }

    public static String profileFor(String manufacturer, String brand) {
        String combined = ((manufacturer == null ? "" : manufacturer) + " "
            + (brand == null ? "" : brand)).toLowerCase(Locale.US);
        if (combined.contains("lenovo")) return "lenovo";
        if (combined.contains("samsung")) return "samsung";
        if (combined.contains("xiaomi") || combined.contains("redmi") || combined.contains("poco")) {
            return "xiaomi";
        }
        if (combined.contains("motorola")) return "motorola";
        if (combined.contains("oneplus") || combined.contains("oppo")
            || combined.contains("realme") || combined.contains("oplus")) {
            return "oplus";
        }
        if (combined.contains("huawei") || combined.contains("honor")) return "huawei";
        if (combined.contains("amazon") || combined.contains("amzn")) return "amazon";
        if (combined.contains("tcl")) return "tcl";
        return "generic";
    }

    public static List<Rule> rulesFor(String manufacturer, String brand) {
        String profile = profileFor(manufacturer, brand);
        Map<String, Rule> unique = new LinkedHashMap<>();
        for (Rule rule : RULES) {
            if ("generic".equals(rule.profile) || profile.equals(rule.profile)) {
                unique.put(rule.packageName, rule);
            }
        }
        return new ArrayList<>(unique.values());
    }

    public static Rule findRule(String packageName, String manufacturer, String brand) {
        if (packageName == null) return null;
        for (Rule rule : rulesFor(manufacturer, brand)) {
            if (packageName.equals(rule.packageName)) return rule;
        }
        return null;
    }

    public static boolean isProtectedPackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return true;
        return PROTECTED_PACKAGES.contains(packageName)
            || packageName.startsWith("com.zui.theme.settings.overlay.");
    }
}
