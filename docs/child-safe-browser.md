# Child-Safe App and Browser Boundary

OpenPanel applies the same child-facing catalog policy on Fire OS, generic
Android tablets, Google TV, and XR. Stores and device-management utilities can
remain installed and enabled for updates or administration without becoming
launcher tiles.

## Packages hidden from the child catalog

The native and React catalogs use the same exact-package policy. It covers
Aurora Store, F-Droid, Google Play, Amazon Appstore, common OEM stores,
unrestricted browsers, Nova Launcher, personalDNSfilter, and Shizuku. These
packages are not uninstalled and are not shown in either the allowed or
available child-app grids. personalDNSfilter can continue running as the VPN/DNS
filter, and stores can continue their administrator-configured update jobs.

The Device Health debloat policy separately offers reversible hiding for exact,
reviewed packages such as Chrome, Firefox, Gallery, Photos, the stock Camera,
Calendar, Contacts, Clock, Email, Music, and the Fire Silk components. Core
Settings, WebView, networking, DocumentsUI, OTA, device policy, System UI,
OpenPanel, and fallback OEM launcher packages remain protected.

## Safe Browser

`SafeBrowserActivity` handles escaped `http` and `https` links without declaring
a launcher category, so it is never an OpenPanel tile. Its controls expose only
Back, Kiddle Home, and Close. The WebView:

- permits only HTTPS URLs whose host is `kiddle.co` or a subdomain;
- blocks navigation away from Kiddle instead of trusting the destination page;
- rejects off-domain page resources as well as off-domain top-level links;
- disables JavaScript, cookies, DOM/database storage, downloads, geolocation,
  file/content access, pop-ups, new windows, and web permission grants;
- rejects TLS errors and returns to safety on a Safe Browsing hit;
- sends Close and root-level Back directly to the OpenPanel launcher.

Kiddle describes itself as a kid-oriented search service, but also warns that
filtering is weaker after leaving its results. OpenPanel therefore does not let
the fallback browser follow external result links. This is a strict escape
containment browser, not a guarantee that every Kiddle page is appropriate for
every four-year-old; an adult should still curate normal OpenPanel content.

## Assigning the browser role

On an authorized development device, assign the debug build with:

```sh
adb shell cmd role add-role-holder --user 0 \
  android.app.role.BROWSER com.orgista.openpanel.debug
```

Use `com.orgista.openpanel` for a signed production build. Managed deployments
should assign the Android browser role through their DPC. If a platform does
not expose roles, an administrator can select OpenPanel Safe Browser from its
default-app UI after opening an HTTP(S) link.

## Reversible device cleanup

Managed generic Android deployments should use Admin Panel → Device Health so
OpenPanel can hide and restore reviewed packages through Android policy. On an
ADB-authorized Fire tablet, exact packages may be disabled for user 0 with
`pm disable-user --user 0 PACKAGE` and restored with `pm enable PACKAGE`. Never
disable packages outside the reviewed catalog or the protected system boundary.
