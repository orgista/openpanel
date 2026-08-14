# TV DNS filtering

OpenPanel supervises a separate DNS-filter app instead of embedding a VPN and
resolver into the launcher. Keeping those responsibilities separate reduces
launcher risk, preserves the Android one-VPN-at-a-time security model, and lets
the DNS component be updated independently.

The tested package is `dnsfilter.android` (personalDNSfilter). OpenPanel's
TV-only **Admin Panel → Device Health → DNS & Telemetry** section reports:

- whether personalDNSfilter is installed and enabled;
- whether its process owns the active VPN network;
- always-on VPN and lockdown state;
- Android Private DNS mode and a warning when it can bypass the local filter;
- whether OpenPanel is allowed to manage always-on VPN as Device Owner.

OpenPanel deliberately does not download, install, or silently authorize the
filter. Android requires one-time user consent before any app can create a VPN.
There can only be one active VPN per Android user, so this design is not
compatible with a second simultaneous VPN client.

Some Android 9 TV firmware honors personalDNSfilter's phone orientation while
the system consent activity is open. On the tested TCL this rotates the whole
display, starts the ambient screen, and makes remote focus unreliable. For that
reason OpenPanel reports the filter state but does not launch its phone UI from
the TV Admin panel. Provision the one-time approval through the deployment
workflow, then return to OpenPanel and verify **Filter VPN: Connected**.

## Recommended TV profile

Use a small, predictable configuration:

- upstream: AdGuard filtered DNS over HTTPS, with its filtered UDP resolvers as
  fallback;
- primary list: 1Hosts Lite only, refreshed every seven days;
- local overrides: [`../assets/dns/tv-additional-hosts.txt`](../assets/dns/tv-additional-hosts.txt);
- traffic logging: off after validation;
- Private DNS: off while the local DNS VPN is active;
- always-on VPN: on;
- VPN lockdown: off unless the fleet administrator has an independently tested
  recovery path.

The local override file is intentionally short. Do not block broad domains such
as `google.com`, `googleapis.com`, `youtube.com`, `googlevideo.com`, `tcl.com`,
Amazon Web Services, CloudFront, or Akamai. Those domains share streaming,
authentication, update, and CDN infrastructure. Validate YouTube, Netflix, OS
updates, captive-portal detection, and device management after every list
change.

DNS filtering can reduce background requests and data transfer. It is not a
guaranteed speed boost: a slow resolver or oversized list can increase latency,
and DNS cannot stop traffic sent to hard-coded IP addresses or an app's own
encrypted resolver.

## Management modes

In standalone mode, OpenPanel can call Android's Device Owner API to select
personalDNSfilter as always-on. OpenPanel always requests `lockdown=false` so a
filter failure does not strand the TV offline.

For an explicitly ADB-managed test TV, an operator can recover an already
configured filter without using the rotated consent UI:

```sh
adb shell appops set dnsfilter.android ACTIVATE_VPN allow
adb shell settings put secure always_on_vpn_app dnsfilter.android
adb shell settings put secure always_on_vpn_lockdown 0
adb shell am start -n dnsfilter.android/.DNSProxyActivity
adb shell input keyevent KEYCODE_HOME
```

The brief activity launch starts the app-owned VPN after authorization; Home
immediately restores the TV's normal landscape launcher. Use this only on a TV
the operator owns or is authorized to administer.

When ArborXR or another DPC owns the device, OpenPanel reports status but does
not override the active DPC. Deploy the filter as its own managed application
and configure always-on VPN through the management policy or the TV's VPN
settings. Do not configure both a local DNS VPN and a strict Private DNS host
unless the resolver is reachable through and outside the VPN.

## Recovery

Every package change in OpenPanel's Device Health policy is exact-name and
reversible. For an ADB-serviced TV, the relevant recovery operations are:

```sh
adb shell settings delete secure always_on_vpn_app
adb shell settings delete secure always_on_vpn_lockdown
adb shell am force-stop dnsfilter.android
adb shell pm enable --user 0 PACKAGE_NAME
```

Restore only packages recorded as disabled during that TV's deployment. Do not
bulk-enable every system package: OEM images contain intentionally disabled
components.
