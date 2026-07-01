package com.orgista.openpanel;

import android.Manifest;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private static final String ARBORXR_DPC = "app.xrdm.client";

    private BroadcastReceiver btScanReceiver;
    private BroadcastReceiver btStateReceiver;
    private final List<JSObject> btScanResults = new ArrayList<>();

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
                    JSObject data = new JSObject();
                    data.put("enabled", state == BluetoothAdapter.STATE_ON);
                    notifyListeners("bluetoothStateChanged", data);
                }
            }
        };
        getContext().registerReceiver(btStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    }

    @Override
    protected void handleOnDestroy() {
        if (btStateReceiver != null) {
            try { getContext().unregisterReceiver(btStateReceiver); } catch (Exception ignored) {}
            btStateReceiver = null;
        }
    }

    // ---------- Apps ----------

    @PluginMethod
    public void getInstalledApps(PluginCall call) {
        PackageManager pm = getContext().getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = pm.queryIntentActivities(main, 0);

        Set<String> seen = new HashSet<>();
        JSArray apps = new JSArray();
        String self = getContext().getPackageName();

        for (ResolveInfo ri : activities) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(self) || !seen.add(pkg)) continue;

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
                app.put("icon", drawableToBase64(pm.getApplicationIcon(ai)));
                apps.put(app);
            } catch (Exception ignored) {}
        }

        JSObject ret = new JSObject();
        ret.put("apps", apps);
        call.resolve(ret);
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
        getContext().startActivity(intent);
        call.resolve();
    }

    // ---------- Battery ----------

    @PluginMethod
    public void getBatteryInfo(PluginCall call) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = getContext().registerReceiver(null, filter);

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
        call.resolve(ret);
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

        WifiManager wifi = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder().setSsid(ssid);
        if (password != null && !password.isEmpty()) {
            builder.setWpa2Passphrase(password);
        }
        List<WifiNetworkSuggestion> suggestions = new ArrayList<>();
        suggestions.add(builder.build());

        // An empty list removes all of this app's previous suggestions, so a
        // re-connect with a corrected password replaces the stale credential.
        wifi.removeNetworkSuggestions(new ArrayList<>());
        int status = wifi.addNetworkSuggestions(suggestions);

        JSObject ret = new JSObject();
        ret.put("status", status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ? "suggested" : "error");
        ret.put("code", status);
        call.resolve(ret);
    }

    @PluginMethod
    public void forgetWifi(PluginCall call) {
        String ssid = call.getString("ssid");
        if (ssid == null) {
            call.reject("ssid is required");
            return;
        }
        WifiManager wifi = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        List<WifiNetworkSuggestion> suggestions = new ArrayList<>();
        suggestions.add(new WifiNetworkSuggestion.Builder().setSsid(ssid).build());
        wifi.removeNetworkSuggestions(suggestions);
        call.resolve();
    }

    @PluginMethod
    public void openWifiSettings(PluginCall call) {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            intent = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
        } else {
            intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
        }
        getActivity().startActivity(intent);
        call.resolve();
    }

    // ---------- Bluetooth ----------

    private BluetoothAdapter getBtAdapter() {
        BluetoothManager bm = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    private boolean hasBtPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return getPermissionState("bluetooth") == PermissionState.GRANTED;
        }
        return true;
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
            call.resolve(ret);
            return;
        }
        ret.put("available", true);
        ret.put("enabled", adapter.isEnabled());

        JSArray devices = new JSArray();
        if (adapter.isEnabled() && hasBtPermissions()) {
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
                    finishBtScan(call);
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        getContext().registerReceiver(btScanReceiver, filter);

        try {
            if (!adapter.startDiscovery()) {
                finishBtScan(call);
            }
        } catch (SecurityException e) {
            finishBtScan(call);
        }
    }

    private void finishBtScan(PluginCall call) {
        if (btScanReceiver != null) {
            try { getContext().unregisterReceiver(btScanReceiver); } catch (Exception ignored) {}
            btScanReceiver = null;
        }
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
        if (adapter == null || !adapter.isEnabled()) {
            call.reject("Bluetooth is disabled", "BT_DISABLED");
            return;
        }
        if (!hasBtPermissions()) {
            call.reject("Bluetooth permission not granted", "PERMISSION_DENIED");
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

            call.setKeepAlive(true);
            BroadcastReceiver bondReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null || !address.equals(d.getAddress())) return;
                    int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                    if (state == BluetoothDevice.BOND_BONDED) {
                        try { context.unregisterReceiver(this); } catch (Exception ignored) {}
                        JSObject ret = new JSObject();
                        ret.put("status", "paired");
                        call.setKeepAlive(false);
                        call.resolve(ret);
                    } else if (state == BluetoothDevice.BOND_NONE) {
                        try { context.unregisterReceiver(this); } catch (Exception ignored) {}
                        call.setKeepAlive(false);
                        call.reject("Pairing failed or was cancelled", "PAIR_FAILED");
                    }
                }
            };
            getContext().registerReceiver(bondReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));

            if (!device.createBond()) {
                try { getContext().unregisterReceiver(bondReceiver); } catch (Exception ignored) {}
                call.setKeepAlive(false);
                call.reject("Could not start pairing", "PAIR_FAILED");
            }
        } catch (SecurityException e) {
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
        if (enabled == adapter.isEnabled()) {
            call.resolve();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBtPermissions()) {
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
        // Apps targeting API 33+ cannot flip Bluetooth silently; both directions
        // go through a system confirmation dialog.
        String action = enabled
            ? BluetoothAdapter.ACTION_REQUEST_ENABLE
            : "android.bluetooth.adapter.action.REQUEST_DISABLE";
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
        getActivity().startActivity(intent);
        call.resolve();
    }

    // ---------- Kiosk lock (standalone / non-ArborXR mode) ----------

    private DevicePolicyManager dpm() {
        return (DevicePolicyManager) getContext().getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    private boolean isDefaultLauncher() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo res = getContext().getPackageManager()
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return res != null && res.activityInfo != null
                && getContext().getPackageName().equals(res.activityInfo.packageName);
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
        ret.put("lockTaskActive", state != ActivityManager.LOCK_TASK_MODE_NONE);
        ret.put("lockTaskMode", lockTaskModeName(state));
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getActivity().startActivity(intent);
        call.resolve();
    }

    @PluginMethod
    public void openLauncherSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_HOME_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getActivity().startActivity(intent);
        } catch (Exception e) {
            // Some OEM builds lack the Home settings screen — fall back to Settings.
            getActivity().startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        call.resolve();
    }

    @PluginMethod
    public void enableKioskLock(final PluginCall call) {
        final DevicePolicyManager dpm = dpm();
        final String pkg = getContext().getPackageName();

        // As Device Owner we allowlist ourselves (plus any caller-supplied apps)
        // so lock-task is the strong, silent kind and launched apps stay pinned.
        // Otherwise startLockTask() falls back to user-confirmed screen pinning.
        if (dpm != null && dpm.isDeviceOwnerApp(pkg)) {
            List<String> allow = new ArrayList<>();
            allow.add(pkg);
            JSArray packages = call.getArray("packages");
            if (packages != null) {
                for (int i = 0; i < packages.length(); i++) {
                    String p = packages.optString(i, null);
                    if (p != null && !p.isEmpty() && !allow.contains(p)) allow.add(p);
                }
            }
            try {
                dpm.setLockTaskPackages(OpenPanelDeviceAdminReceiver.getComponentName(getContext()),
                        allow.toArray(new String[0]));
            } catch (Exception ignored) {}
        }

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    getActivity().startLockTask();
                    JSObject ret = new JSObject();
                    ret.put("status", lockTaskModeName(lockTaskState()));
                    call.resolve(ret);
                } catch (Exception e) {
                    call.reject("Could not enter kiosk lock: " + e.getMessage(), "LOCK_FAILED");
                }
            }
        });
    }

    @PluginMethod
    public void disableKioskLock(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    getActivity().stopLockTask();
                    call.resolve();
                } catch (Exception e) {
                    call.reject("Could not exit kiosk lock: " + e.getMessage(), "UNLOCK_FAILED");
                }
            }
        });
    }
}
