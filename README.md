# OpenPanel

**The single source of truth for this repository.** The shared product overview plus the Google TV and Amazon Fire OS platform profiles all live in this one file.

> The React/Vite UI lives in the separate **openpanel-ui** repository, checked out at `src/` with its own `README.md` and MIT license. That boundary is deliberate (open-core split) — do not merge the two documents.

## What OpenPanel is

A managed **kiosk launcher** for Android tablets, Google TV, and XR headsets (Meta Quest, Pico, Vive Focus, Lenovo, …). One adaptive app presents an approved catalog of apps, approved YouTube videos/channels/playlists, and an offline books/audiobooks shelf with standards-based institutional catalogs — and confines the device to that approved surface.

## Contents

0. [Working in this repo as an agent](#working-in-this-repo-as-an-agent) — read first if you are a model or a new engineer
1. [Mobile, tablet, and XR (shared product baseline)](#mobile-tablet-and-xr)
2. [Google TV and Android TV](#google-tv-and-android-tv)
3. [Amazon Fire OS](#amazon-fire-os)

---

## Working in this repo as an agent

Written 2026-08-17 so that a smaller model (Sonnet, Kimi, GLM, DeepSeek, …) or a
new engineer can take a scoped task and finish it without breaking a build or a
device. This is the contract; the rest of this README is the reference. If you
only read one section, read this one and then run the verification commands.

### 1. Two repositories, one checkout

| Path | What it is | Commit here for |
|---|---|---|
| `./` (this repo, **openpanel**) | Android engine: Capacitor host, native Java/Kotlin under `android/`, build/deploy scripts, this README | Anything under `android/`, `scripts/`, `package.json`, root config |
| `./src/` (**openpanel-ui**, its own `.git`) | The React/Vite UI. Git-ignored by the outer repo (`.gitignore` line `/src/`) | Anything under `src/` |

`git status` at the root will never show `src/` changes. Run `git -C src status`
too. Never try to add `src/` to the outer repo, and never merge the two READMEs.

### 2. "All builds" means one codebase with runtime branches

There are **no Gradle product flavors** and no per-device forks. One APK is built;
behaviour differs by the `deviceProfile` object the native side reports
(`SystemBridgePlugin.getDeviceProfile()`, consumed throughout `src/app`):

| Build people talk about | Distinguishing profile flags | Notes |
|---|---|---|
| Mobile / tablet (ArborXR companion **or** standalone) | `isTablet` / `isHandheld`, `touchUi`, `isStandalone` prop | Baseline behaviour |
| Google TV / Android TV | `isTelevision`, `remoteUi`, `hasDpad`; `sdk <= 28` = legacy TV | D-pad focus, collapsed Wi-Fi list, no backdrop blur on legacy |
| Amazon Fire OS | `isFireOs` (from `LandscapeOrientationLock.isFireDevice`) | Fire OS owns Wi-Fi (system picker); app is **not** Device Owner there |
| Debug vs release | package `com.orgista.openpanel.debug` vs `com.orgista.openpanel` | Different signing keys — see §5 |

So a fix in a shared component (for example `src/app/components/SettingsModal.tsx`)
lands on every build **unless it sits inside a `deviceProfile` branch**. When a
task says "on all builds", check each branch of the flags above, not separate
files.

### 3. Verify before you report — these are the gates

From the repo root, in this order. All three must pass on a clean tree before a
UI or shared change is "done":

```sh
npm run typecheck      # tsc --noEmit — covers src/ via tsconfig "include": ["src"]
npm test               # vitest run — src/**/*.test.{ts,tsx}
npm run build          # vite build
```

Android native change? Add:

```sh
npm run verify:versions                    # package.json vs android/app/build.gradle
bash scripts/gradle-local.sh :app:testDebugUnitTest   # JDK 21 required
```

The Android *build directory cannot live on the SMB share* — packaging fails late
with `Unable to delete directory … packageDebug/tmp`. Use `scripts/gradle-local.sh`
for tests/lint, and the local-disk copy recipe in
[Build environment and known environment traps](#build-environment-and-known-environment-traps)
for `assembleDebug`/`assembleRelease`. Do not "fix" that by moving `build/` in
Gradle config.

### 4. Definition of done for a scoped task

- The change is the **minimum** that satisfies the task. No drive-by refactors,
  renames, dependency additions, or formatting sweeps.
- Existing tests updated, and a test added for the behaviour you changed
  (pattern: `renderToStaticMarkup(<Component …/>)` + string assertions, see
  `src/app/components/*.test.tsx`).
- Gates in §3 pass. Say which you ran and the pass/fail result. If a gate fails
  for a pre-existing reason unrelated to your change, say so explicitly.
- UX copy follows house style: plain, short, no em dashes, no "AI-tell" phrasing.
- **Do not commit or push unless the task says to.** Leave the diff in the tree
  and report `git status` + `git -C src status`. Never bump the version numbers as
  part of a feature; releases are separate commits (`release: bump to x.y.z`).
- Report in this shape: `Done` (item → files → how) / `Not done` (item → why) /
  `Gates run` / `Notes for the reviewer`.

### 5. Do-not-touch unless the task is explicitly about it

- **Signing and keys**: `private/`, `*.keystore`, `~/.android/debug.keystore`,
  `scripts/build-release-keychain.sh`, `scripts/arborxr-cli-keychain.sh`. The Fire
  tablet can only be upgraded by the Mac Studio's debug key
  (`11b408c4…36f8`); a wrong key strands the device.
- **Deploy scripts** (`scripts/deploy-*.sh`, `configure-arborxr-token.command`)
  and `.github/workflows/`. Never run a deploy or `adb install` unless asked.
- **`android/local.properties`** — shared across machines through the NAS; only
  repoint `sdk.dir` when you are actually building on this machine.
- **Kiosk safety code**: `MainActivity` lock-task handling, `KioskLock`,
  `KioskBootReceiver`, `OpenPanelDeviceAdminReceiver`, `ProvisioningModeActivity`,
  `SystemBridgePlugin.requireKioskAdmin()` and every method it guards
  (`enableKioskLock`, `disableKioskLock`, `exitKioskToSystemHome`,
  `clearDeviceOwner`), `HomeGestureAccessibilityService`, `FireLauncherRedirect`.
- **Device policy lists**: `DebloatCatalog`, `NotificationBlockPolicy`,
  `SafeBrowserPolicy`, `KioskVolumePolicy`.
- `artifacts/` (built APKs are evidence, not scratch), `Samples/`,
  `android/app/src/main/assets/public` (generated by `npx cap sync`).

### 6. Admin gating — the one rule to internalise

The kiosk has two levels: **guest** (`isAdmin === false`) and **admin** (PIN
verified, `isAdmin === true`). The principle that decides which side an action
belongs on:

> Anything **destructive**, **persistent for other users**, or that **leaves the
> kiosk surface** (opens Android Settings, calls `unpinIfPinned()`, exits kiosk) is
> admin-only. Anything additive and reversible that a guest needs to use the
> device may be guest-allowed.

Concretely: forgetting a Wi-Fi network, unpairing Bluetooth, turning radios off,
opening full system settings, exiting kiosk, changing the catalog → admin.
Scanning, joining a network, pairing a controller → may be guest (see the
Settings section for the current policy). When you relax a gate, keep the
admin-only controls admin-only in the **same** component and add a test that
renders with `isAdmin={false}` and asserts the destructive control is absent.

The native layer only enforces admin for kiosk-lock/Device-Owner methods (via
`adminToken`); every other gate lives in the UI. That is deliberate — the UI is
the policy layer for connectivity — so a UI-only change is the correct shape for
a gating change.

### 7. Where things are

- Settings modal (Wi-Fi/Bluetooth/admin footer): `src/app/components/SettingsModal.tsx`
- Admin panel: `src/app/components/AdminPanel.tsx`; PIN pad: `PinInput.tsx`
- Native bridge (TS side): `src/app/native/SystemBridge.ts`; Java side:
  `android/app/src/main/java/com/orgista/openpanel/SystemBridgePlugin.java`
- App shell/state: `src/app/App.tsx`
- Device profile / Fire detection: `SystemBridgePlugin.getDeviceProfile()`,
  `LandscapeOrientationLock.isFireDevice()`
- Per-platform behaviour and device state: the platform sections below and
  [Fire tablet handoff](#fire-tablet-handoff--open-work-as-of-2026-08-17)

---

## Mobile, tablet, and XR

## OpenPanel — Mobile & Tablet

A managed **kiosk launcher** for Android tablets, Google TV, and XR headsets
(Meta Quest, Pico, Vive Focus, Lenovo, …). One adaptive app presents an approved
catalog of apps, approved YouTube videos/channels/playlists, and an offline
books/audiobooks shelf with standards-based institutional catalogs, and confines
the device — either alongside **ArborXR** (companion mode) or on its own
(standalone kiosk).

This is the **primary/base document** for the project. Device-specific behaviour
lives in two companion sections below:

- [the Google TV section](#google-tv-and-android-tv) — Google TV / Android TV / Leanback.
- [the Fire OS section](#amazon-fire-os) — Amazon Fire OS tablets.

- **Package:** `com.orgista.openpanel`
- **One APK, all form factors:** phone/tablet (`LAUNCHER`), Google TV
  (`LEANBACK_LAUNCHER` + banner), and XR headsets (touchscreen not required, runs
  as a 2D panel — the most compatible XR approach across every ArborXR-managed
  headset).
- **Min SDK 24 / compile & target SDK 36**, landscape.

### Management modes

Auto-detected on first run:

- **Companion** — ArborXR is the Device Owner and handles lockdown; OpenPanel is
  the launcher UI on compatible non-Fire devices. Auto-selected when
  `app.xrdm.client` is the Device Owner.
- **Standalone** — OpenPanel becomes the HOME launcher and locks the device
  itself (device admin + lock task / screen pinning) for setups without ArborXR.
- **Fire OS** — always forced to standalone Home/accessibility redirection;
  ArborXR and Device Owner enrollment are not offered. See
  [the Fire OS section](#amazon-fire-os).

### Feature summary

- **Restricted YouTube sources:** admins can approve individual videos,
  playlists, canonical channel URLs, modern `@handle` channel URLs, and legacy
  channel usernames without configuring a YouTube API key. Channel names and
  handles are verified to a canonical channel ID before they can be added.
  Channel tiles open a recent-video browser backed by YouTube's public channel
  feed, allowing the user to choose a video before it opens in the restricted
  privacy-domain player. Full detail in [the Google TV section](#google-tv-and-android-tv).
- **Device Health:** detects manufacturer/model and Android version, reports live
  RAM and storage use, and applies a conservative OEM-aware debloat policy in
  standalone Device Owner mode. Package changes are hidden reversibly and can be
  restored from the Admin Panel. Notification management is enabled by default on
  Android 13+ and restores only grants previously changed by OpenPanel.
- **Books & Audio:** imports EPUB, PDF, Readium audiobook, MP3, and AAC files;
  reads OPDS 1.2/2.0 catalogs without WebView CORS restrictions; stores
  publications privately for offline use; resumes reading/listening with Readium
  navigators.
- **Child-safe app and web boundary:** storefronts, recovery launchers,
  unrestricted browsers, and background admin utilities can stay installed but
  never appear as child-facing launcher tiles.
- **Forward-compatible Android build:** no maximum Android version is declared;
  the stable Capacitor 8 toolchain compiles and targets API 36 and is designed
  for Android 17/API 37 runtime compatibility without adopting the preview target
  SDK in production.

---

### Device Health and debloat policy

OpenPanel's Device Health engine is an MIT-licensed, auditable Android Device
Owner policy. The reviewed package catalog lives at
[`android/app/src/main/java/com/orgista/openpanel/DebloatCatalog.java`](android/app/src/main/java/com/orgista/openpanel/DebloatCatalog.java).

#### What it does

- Detects the manufacturer, brand, model, device, product, Android version, and
  matching policy profile on the device itself.
- Reports total, available, and used RAM plus internal storage usage.
- Detects reviewed optional packages using exact package names.
- Hides packages with `DevicePolicyManager.setApplicationHidden`. Hiding is
  reversible and does not change Android's read-only system partition.
- Tracks the packages hidden by OpenPanel and restores only that tracked set.
- Lets an administrator request Android's ordinary uninstall confirmation for
  eligible user-installed apps. System apps are hidden, not uninstalled.
- On Android 13 and later, suppresses `POST_NOTIFICATIONS` for eligible apps when
  **Manage app notifications** is on. The toggle defaults to on. OpenPanel
  records only permissions it changes, so turning the toggle off restores that
  recorded set.

The initial generic policy includes Play Books, Play Games, YouTube, YouTube
Kids, Kids Space, Keep Notes, Google Wallet/GPay, YouTube Music, Google Feedback,
and Google Location History. OEM profiles add conservative packages for Lenovo,
Motorola, Samsung, Xiaomi/Redmi/Poco, OnePlus/Oppo/Realme, Huawei/Honor, and
Amazon Fire OS. For example, the Lenovo profile includes Lenovo FreeStyle and
App Explorer.

#### Why it does not run ADB

An ordinary Android app cannot safely run ADB against its own device. ADB is a
developer/host transport controlled by Android's debugging service; bundling a
root or local shell workaround would weaken the kiosk security model.

OpenPanel instead uses Android's supported on-device management boundary:

- **OpenPanel Device Owner:** package and notification policy can be applied
  silently and reversibly through `DevicePolicyManager`.
- **ArborXR Device Owner:** ArborXR owns the Device Policy Controller slot.
  OpenPanel detects this state and reports that the equivalent app and
  notification policy must be configured in ArborXR. Android permits one Device
  Owner, so OpenPanel does not try to override ArborXR.
- **Unmanaged/Device Admin only:** health data and recommendations remain
  visible, but Android will not allow OpenPanel to silently manage other apps.

#### Safety boundaries

The catalog never pattern-matches unknown packages. It hard-protects core
Android services, Settings, System UI, phone/emergency components, permission
and package installers, Google Play services/framework, current launchers,
wallpaper providers, Lenovo OTA/management agents, ArborXR's DPC and launcher,
and OpenPanel's production/debug packages.

Apps disabled with ADB, an OEM tool, or a different DPC are shown as **Disabled
outside OpenPanel**. OpenPanel does not claim it can restore changes it did not
make. Restore actions only reverse the OpenPanel-tracked hidden-package and
notification sets.

#### Adding or reviewing a package

1. Confirm the package name on a device you own or are authorized to manage.
2. Identify the exact user-facing purpose and the applicable OEM profile.
3. Verify it is not required for boot, setup, emergency use, the launcher,
   wallpaper, OTA, accessibility, permissions, or device management.
4. Add one exact `rule(...)` entry to `DebloatCatalog.java`.
5. Add or update a unit test in
   `android/app/src/test/java/com/orgista/openpanel/DebloatCatalogTest.java`.
6. Run `./scripts/gradle-local.sh :app:testDebugUnitTest :app:lintDebug` and test
   hide plus restore on the applicable device before promotion.

---

### Child-safe app and browser boundary

OpenPanel applies the same child-facing catalog policy on Fire OS, generic
Android tablets, Google TV, and XR. Stores and device-management utilities can
remain installed and enabled for updates or administration without becoming
launcher tiles.

#### Packages hidden from the child catalog

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

#### Safe Browser

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

#### Assigning the browser role

On an authorized development device, assign the debug build with:

```sh
adb shell cmd role add-role-holder --user 0 \
  android.app.role.BROWSER com.orgista.openpanel.debug
```

Use `com.orgista.openpanel` for a signed production build. Managed deployments
should assign the Android browser role through their DPC. If a platform does not
expose roles, an administrator can select OpenPanel Safe Browser from its
default-app UI after opening an HTTP(S) link.

#### Reversible device cleanup

Managed generic Android deployments should use Admin Panel → Device Health so
OpenPanel can hide and restore reviewed packages through Android policy. On an
ADB-authorized Fire tablet, exact packages may be disabled for user 0 with
`pm disable-user --user 0 PACKAGE` and restored with `pm enable PACKAGE`. Never
disable packages outside the reviewed catalog or the protected system boundary.

---

### Books & Audio for libraries and public institutions

OpenPanel's library feature is designed around open publishing standards rather
than one commercial vendor. It uses the BSD-3-Clause-licensed Readium Kotlin
Toolkit for EPUB, PDF, packaged audiobooks, and standalone audio, and supports
OPDS 1.2 and OPDS 2.0 catalogs for discovery and acquisition.

#### Supported workflows

- Import local EPUB, PDF, Readium audiobook, MP3, M4A/M4B, or AAC files with
  Android's system file picker.
- Configure HTTPS OPDS catalogs during setup or in **Admin Panel → Books &
  Audio**.
- Browse approved catalogs from the launcher and download compatible
  open-access publications into OpenPanel's app-private storage.
- Read EPUBs with font-size, light/dark theme, touch-edge, keyboard, and D-pad
  navigation.
- Read PDFs with Readium's PDFium navigator.
- Play audiobooks with play/pause, 30-second skip, seeking, media-volume
  routing, and saved position.
- Resume the most recently opened publication from its stored Readium Locator.

OpenPanel does not bundle or preconfigure a catalog. Administrators may add
Project Gutenberg or their library's own approved OPDS endpoint after deployment
without modifying the app.

#### Lending, authentication, and DRM boundary

OPDS is a discovery/acquisition protocol, not a universal library account. A
catalog entry may advertise a loan, but OpenPanel does not collect patron
credentials or attempt to bypass DRM. Palace, Libby/OverDrive, Hoopla,
cloudLibrary, and similar licensed collections must use an integration and
authentication flow authorized by the library and vendor.

Readium LCP can be added after the deploying institution supplies the licensed
native `liblcp` integration and completes any required certification. Without
that connector, OpenPanel clearly reports that a restricted publication needs the
institution's licensed connector.

#### Security and privacy

- Catalogs and publication downloads must use HTTPS, including redirects.
- Catalog responses are capped at 5 MB and publication downloads at 1 GB.
- Files are copied into app-private storage; OpenPanel never grants another app
  broad storage access.
- Catalog credentials are not stored in this release.
- Book metadata and reading positions stay on the device and are excluded from
  Android backup with the rest of OpenPanel's private data.
- Deleting a publication removes both its private file and its metadata.

---

### Managed Android provisioning (Device Owner)

OpenPanel keeps managed-device enrollment instructions in the repository rather
than displaying a provisioning QR on the target device. A QR shown by that tablet
is unavailable after the factory reset that managed provisioning needs.

These instructions are for compatible generic Android tablets, Google TV, and XR
deployments. The OpenPanel Fire profile deliberately does not offer ArborXR or
Device Owner enrollment; use the Fire standalone procedure in
[the Fire OS section](#amazon-fire-os).

#### Development provisioning with ADB

Android only accepts a Device Owner while the device has no accounts and is not
already managed. Use a fresh test device or remove accounts and users as allowed
by that device's Android build, install the intended APK, then run exactly one of
these commands from an authorized workstation:

```sh
# Debug build
adb shell dpm set-device-owner \
  com.orgista.openpanel.debug/com.orgista.openpanel.OpenPanelDeviceAdminReceiver

# Signed production build
adb shell dpm set-device-owner \
  com.orgista.openpanel/com.orgista.openpanel.OpenPanelDeviceAdminReceiver
```

Verify the result before deployment:

```sh
adb shell dpm list-owners
adb shell dumpsys device_policy
```

Do not run `set-device-owner` against a personal device or a tablet that already
contains user data. Removing Device Owner changes device-management state and a
failed or incompatible provisioning attempt can require another factory reset.

#### QR enrollment on compatible generic Android

If a supported device uses Android's setup-wizard QR enrollment, create and save
the enrollment QR on a separate administrator computer or phone **before**
resetting the target. The payload must identify OpenPanel's device-admin
component, a reachable HTTPS APK download, and the signing-certificate checksum
for that exact APK. Keep the QR with the deployment record; never rely on a copy
displayed inside the target app.

QR support is controlled by the device's setup wizard and OEM management stack.
If the setup wizard does not expose managed QR enrollment, use the supported
EMM/DPC workflow for that device or the ADB development procedure above. An
installed Android app cannot promote itself to Device Owner.

As of 1.1.43 the app declares the Android 12+ provisioning handlers
(`GET_PROVISIONING_MODE`, `ADMIN_POLICY_COMPLIANCE`) the setup wizard requires;
earlier builds abort QR enrollment on modern Android.

##### QR payload

```json
{
 "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
   "com.orgista.openpanel/com.orgista.openpanel.OpenPanelDeviceAdminReceiver",
 "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "<HTTPS APK URL>",
 "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": "<base64url of signing-cert SHA-256>",
 "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true
}
```

- Checksum for the production key (cert `94323ad0…`):
  `lDI60CvhV8aBJgXvlw9BPy8Vcet7I83IkkH4EW6Zuww` — derive with
  `echo <cert-sha256-hex> | xxd -r -p | base64 | tr '+/' '-_' | tr -d '='`.
- For the download URL, the ArborXR version `downloadUrl`
  (`GET /api/v3/apps/{appId}/versions`) works; it is a signed link that can
  expire, so regenerate the QR if the wizard reports a download failure.
- Render the minified JSON as a QR (any generator; error correction M).
- Enrollment: factory-reset target → tap the welcome screen six times → join
  Wi-Fi when prompted → scan → the wizard downloads the APK, verifies the
  checksum, and sets OpenPanel as Device Owner.

---

### Build

The full APK build needs the UI repository present at `src/`. With it in place:

```sh
npm ci
npm test                 # UI regression tests
npm run typecheck        # TypeScript validation
npm run build            # vite: src/ -> dist/
npx cap sync android     # copy the web bundle + plugins into the native project
./scripts/gradle-local.sh :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Android native compilation requires **JDK 21**. Java 17 fails Capacitor's source
level 21, and newer JDKs can fail Android's `jlink` transform. Set `JAVA_HOME` to
a real JDK 21 install before running Gradle locally. On macOS, Android Studio's
bundled JBR is a convenient JDK 21:
`/Applications/Android Studio.app/Contents/jbr/Contents/Home`.

The repo lives on an SMB share that can break Gradle locking/cleanup, so use the
local helper (which puts Gradle caches on local temporary storage), a local-disk
checkout, or CI for release builds.

Debug builds are signed with the standard Android debug key — fine for dev, never
trusted for production updates.

The engine CI (`.github/workflows/android.yml`) runs native tests, lint, debug
assembly, and instrumentation-test compilation. The protected manual release
verification workflow checks out an explicitly pinned UI revision and builds a
signed APK with external signing inputs, then verifies its package, SDK range, TV
eligibility, and exact production signing-certificate digest.
`android/app/build.gradle` refuses release tasks unless `OPENPANEL_KEYSTORE_FILE`,
`OPENPANEL_KEYSTORE_PASS`, `OPENPANEL_KEY_ALIAS`, and `OPENPANEL_KEY_PASS` are
provided from CI or a secret manager. The signing preflight is wired into
APK/bundle packaging tasks: a release build with missing signing inputs fails
instead of falling through to an unsigned APK.

#### Release signing

Release signing material is **not** stored in this repo. Release packaging tasks
require external signing inputs and fail instead of producing an unsigned APK:

- `OPENPANEL_KEYSTORE_FILE`
- `OPENPANEL_KEYSTORE_PASS`
- `OPENPANEL_KEY_ALIAS`
- `OPENPANEL_KEY_PASS`

For CI, store the keystore as `RELEASE_KEYSTORE_BASE64`, decode it into a runner
temp file, and export `OPENPANEL_KEYSTORE_FILE` to that path before running
`./gradlew assembleRelease`. For local signing, use the ignored, owner-only NAS
file at `private/signing/openpanel-production.keystore` and retrieve its password
from macOS Keychain. The old `openpanel-upload.keystore` file and its
compatibility symlink are retained only as legacy records; they cannot sign
releases made with the replacement key.

Keep the upload key stable and managed in a secret manager / offline signer:
Android rejects updates signed by a different key. Keystores are git-ignored
(`*.keystore`); never commit one.

The legacy signing password appeared in local agent history and was redacted. The
replacement production key uses a distinct password stored in Keychain and in an
independent owner-controlled recovery vault. Never copy signing passwords into
documentation, tickets, chat, or command output.

Before upload, verify the produced APK:

```sh
apksigner verify --print-certs android/app/build/outputs/apk/release/app-release.apk
aapt dump badging android/app/build/outputs/apk/release/app-release.apk | grep -E "package:|sdkVersion|targetSdkVersion"
```

For local releases, `scripts/build-release-keychain.sh` retrieves the password
from macOS Keychain, runs native tests and lint, builds the signed APK, verifies
its identity, and writes a SHA-256 checksum without exposing the password in
shell history or Gradle properties.

CI secret setup:

```sh
PASS="$(security find-generic-password -a openpanel-production-v2 -s "OpenPanel production signing key v2" -w)"
REPO="orgista/openpanel"
KEYSTORE="private/signing/openpanel-production.keystore"
base64 -i "$KEYSTORE" | gh secret set RELEASE_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$PASS"       | gh secret set OPENPANEL_KEYSTORE_PASS --repo "$REPO"
printf '%s' "$PASS"       | gh secret set OPENPANEL_KEY_PASS      --repo "$REPO"
printf 'openpanel-production-v2' | gh secret set OPENPANEL_KEY_ALIAS --repo "$REPO"
```

Set the protected environment variable `OPENPANEL_SIGNING_CERT_SHA256` to the
production certificate's SHA-256 digest (as printed by `apksigner`) so a wrong or
rotated key cannot silently produce an incompatible update.

---

### Deploy — ArborXR

For compatible non-Fire devices, upload the signed APK to **ArborXR** and assign
it to a device/group. On ArborXR-managed devices OpenPanel runs in companion mode
automatically; on unmanaged devices, use the standalone kiosk flow. Fire tablets
use the Fire standalone flow and are not ArborXR deployment targets in this
project.

Use the single existing ArborXR app entry for `com.orgista.openpanel`. Do not
create a separate app entry or package for debug/testing deployments. All signed
builds use the production signing lineage and are separated by release channels
on the same app entry.

ArborXR's special `Latest` channel automatically advances when a newer APK is
uploaded, including an upload explicitly associated with another channel. It must
therefore not be assigned to production groups when releases require a
test-before-promotion gate. Use this channel layout instead:

- `Production`: pinned to the last approved build and assigned to production
  groups.
- `Debug`: points to the candidate build and is assigned only to named test
  devices.
- `Latest`: automatic ArborXR channel; leave it unassigned.

Promote only after device testing passes by changing `Production` to the
already-uploaded candidate build. Before every upload, audit all group and device
assignments and fail if `Latest` or `Debug` is assigned outside its intended
scope.

The guarded release helper enforces that sequence and verifies the APK package,
version, checksum, signer, release-channel identities, test-device identity, and
all OpenPanel group/device assignments before it changes remote state:

```sh
./scripts/deploy-arborxr-beta.sh verify
./scripts/deploy-arborxr-beta.sh status
./scripts/deploy-arborxr-beta.sh test-candidate
./scripts/deploy-arborxr-beta.sh deploy-production
```

`test-candidate` uploads the exact verified APK if needed, pins `Debug` to that
checksum-matched build, waits for it on the online, ungrouped `Pink TCL` test
device, and records the tested build locally under ignored `private/deployment/`.
`deploy-production` refuses to run unless that exact checksum is recorded as
tested; it then updates only the existing `Production` channel for `Kids`,
`Boys`, and `Music`. Offline production devices remain queued for their next
ArborXR check-in. `./scripts/deploy-arborxr-beta.sh deploy` runs both guarded
stages in order.

Every update must use the same production key. Android rejects an update signed
by a different key.

---

### Open-source structure

OpenPanel is MIT-licensed. The native kiosk engine/build tooling and React UI are
versioned in separate repositories. The public CyberBanksy mirrors are
**`cyberbanksy/openpanel`** and **`cyberbanksy/openpanel-ui`**. There is a single
app: the Capacitor app `com.orgista.openpanel` (the legacy AOSP launcher and the
ArborXR SDK were removed).

| Path | Repo | Notes |
| --- | --- | --- |
| `android/` (Capacitor native, `SystemBridgePlugin`, `OpenPanelDeviceAdminReceiver`, manifest, gradle) | **public** `orgista/openpanel` | The kiosk engine + native bridge |
| `scripts/`, build config (`package.json`, `vite.config.ts`, `capacitor.config.ts`, `tsconfig.json`, `index.html`, `postcss.config.mjs`) | **public** `orgista/openpanel` | The UI builds against these |
| `.github/workflows/` | **public** `orgista/openpanel` | Engine CI plus protected full-release verification |
| `src/` (entire React app + TS bridge bindings, styles, assets) | **public** `cyberbanksy/openpanel-ui` | MIT UI source — **git-ignored here** because it is a nested repository |

The UI repo's root maps 1:1 onto `src/` and is excluded here via `.gitignore`
(`/src/`). To build the full app, clone
`https://github.com/cyberbanksy/openpanel-ui.git` to `src/`. That keeps `vite`,
`index.html` → `/src/main.tsx`, and `tsconfig` working unchanged. It can also be
wired as a git submodule at `src/`:

```sh
# (first remove the /src/ ignore line from .gitignore)
git submodule add https://github.com/cyberbanksy/openpanel-ui.git src
git commit -am "build: add UI submodule at src/"
git push
```

Clone for development: `git clone --recurse-submodules <url>`. A public UI needs
no checkout token. Keep `UI_SUBMODULE_TOKEN` only when a private upstream mirror
is intentionally selected in CI.

#### What is NOT published (git-ignored)

`/src/` (separate UI checkout), keystores (`*.keystore`), `.env*`, `Samples/`
(commercial Fully Kiosk APKs — do not redistribute), `*.apk` / `*.zip`, `dist/`,
`build/`, `node_modules/`, `.toolchains/`, `local.properties`, `guidelines/`
(design template), and `docs/_archive/` (retired internal/research notes).

Verify before any push: `git ls-files | grep -iE 'keystore|\.env|password|secret'`
must be empty.

---

### Storage layout

#### Canonical location

All durable OpenPanel data belongs under this NAS folder:

`/Volumes/Files/Projects/Code, Apps & Websites/Apps/OpenPanel`

That folder contains the public engine repository, the private UI repository at
`src/`, source assets, screenshots, local reference material, documentation, and
project-specific toolchains. The convenience path at
`~/Documents/Files/Projects/Apps/OpenPanel` is only a symbolic link to this NAS
folder; it is not a second copy.

NPM's project cache is configured as `.npm-cache/`, so future package downloads
remain under the canonical folder. `node_modules/`, build outputs, and other
generated files also live below the project root when created there.

#### Android build exception

Gradle cannot keep its live cache on this SMB share. macOS reports
`Operation not supported` when Gradle tries to acquire the native file lock. The
local helper therefore uses one disposable folder named `openpanel-gradle-cache`
under macOS's temporary directory. By default it is deleted automatically when
the build exits.

For repeated local builds, set `OPENPANEL_KEEP_LOCAL_CACHE=1` to retain that
cache temporarily, then remove it with:

```sh
npm run storage:clean-local
```

The Android SDK, JDK, Git credentials, and npm's global installation are shared
machine tools rather than OpenPanel-owned data. CI signing copies remain in the
protected CI environment; the local production upload key is stored in the
ignored, owner-only `private/signing/` area.

Run `npm run storage:audit` to confirm the canonical location and detect
OpenPanel-named files or folders in common local and temporary locations.

#### Private records

Non-sensitive historical outputs live physically under
`private/external-records/`. Their former Raster and Unraid paths are symbolic
links, so those systems can still read the same checksummed files without keeping
duplicate data.

Strict consolidation places sensitive OpenPanel data in these ignored,
owner-only locations:

- `private/signing/openpanel-production.keystore` — the active replacement
  signing key for `com.orgista.openpanel`. Its password is stored in macOS
  Keychain and in an independent owner-controlled recovery vault.
- `private/signing/openpanel-upload.keystore` — the inaccessible legacy key.
  `~/openpanel-upload.keystore` remains a compatibility symlink to this preserved
  legacy file.
- `private/agent-state/claude/current/` — current OpenPanel-specific Claude
  project state.
- `private/agent-state/claude/archive-20260718/` — archived project state.

Verify redaction without printing the credential:

```sh
node scripts/redact-openpanel-agent-secrets.mjs --verify
```

Other products and operations reports may legitimately mention OpenPanel. Those
cross-project references stay with their owning systems. The storage audit
enforces consolidation of OpenPanel-owned files and dedicated records, not
removal of every textual reference to the product.

---

### Security

- No signing keys or secrets in source (keys are git-ignored; release signing
  material lives in CI secrets / a secret manager).
- App backup disabled; admin PIN / recovery stored as salted PBKDF2 verifiers
  with persistent lockout; privileged Wi-Fi/Bluetooth/settings actions are
  admin-gated.
- Debloating uses reviewed exact package names with hard protections for core
  Android, emergency, launcher, wallpaper, OTA, DPC/ArborXR, and OpenPanel
  packages. There are no package-name wildcards and no self-ADB or root shell.

---

### Repository policies

#### Master content configuration policy

OpenPanel must ship with an empty user-content catalog. Never hardcode requested
apps, package selections, websites, URLs, YouTube videos/channels/playlists,
library catalogs, media, titles, thumbnails, ordering, or deployment-specific
content in TypeScript, JavaScript, Java, Kotlin, Android resources, bundled
assets, build scripts, or production defaults.

When the user asks to add or change deployable content, record every requested
item in `POST_DEPLOYMENT_CONTENT.txt` instead. That file is a human operations
checklist only: application code, tests, builds, seeders, and deployment scripts
must never parse, import, copy, or automatically apply it. Content must be added
after deployment through OpenPanel's admin UI or the authorized device-management
workflow, and only to the explicitly requested devices or groups.

For each requested item, record its type, display name, URL or identifier,
intended target, ordering/metadata requirements, and deployment status. Do not
put credentials, API keys, tokens, private URLs, or other secrets in the file. If
OpenPanel has no post-deployment configuration path for a requested item, record
the gap and report it rather than hardcoding a workaround.

This policy does not prohibit product-owned branding, ordinary interface copy,
protocol/provider endpoints needed to implement a feature, security allowlists,
device/package detection policy, or clearly isolated test fixtures that cannot
enter a production build. These exceptions must not be used to smuggle a
deployable content catalog into the application.

#### ArborXR upload policy

Agents may build and verify OpenPanel APKs locally without additional
authorization.

Agents may sign, upload, replace, promote, or deploy an OpenPanel production or
debug APK to the configured ArborXR tenant only when the user explicitly asks for
that upload or deployment. Agents must never initiate an external upload or
deployment merely because a new build is available.

For an explicitly requested deployment, agents may reuse the configured ArborXR
token and signing material, update the relevant OpenPanel application entry,
assign the requested existing tablet groups or testing devices, and configure
OpenPanel as the launcher when requested. This is an exception to the workspace
rule prohibiting external APK uploads only for that user-requested OpenPanel
deployment.

Before uploading, agents must verify tests, package identity, version, signature,
and intended deployment targets. Agents must not expose credentials, delete
devices or groups, modify unrelated applications, or deploy an unverified build.

---

### License

MIT — see [LICENSE](LICENSE). © 2026 Orgista.

#### Third-party notices

OpenPanel is MIT-licensed. Its Android Books & Audio feature also incorporates
the following open-source libraries through Maven dependencies:

- **Readium Kotlin Toolkit 3.3.0**, copyright Readium Foundation contributors,
  licensed under the BSD 3-Clause License:
  <https://github.com/readium/kotlin-toolkit/blob/3.3.0/LICENSE>
- **AndroidX Media3**, copyright The Android Open Source Project, licensed under
  the Apache License 2.0:
  <https://github.com/androidx/media/blob/release/LICENSE>
- **PdfiumAndroid**, distributed as a transitive dependency of Readium's PDFium
  adapter under its published open-source license:
  <https://github.com/marain87/PdfiumAndroid>
- **OPDS 2.0 specification** — the interoperable catalog model used by the
  Books & Audio feature: <https://specs.opds.io/opds-2.0>

The complete dependency graph and each artifact's license metadata remain
available from the Gradle/Maven coordinates declared in
`android/app/build.gradle`.

#### UI attributions

The launcher UI includes components from
[shadcn/ui](https://ui.shadcn.com/) used under the
[MIT license](https://github.com/shadcn-ui/ui/blob/main/LICENSE.md).

The launcher UI includes photos from [Unsplash](https://unsplash.com) used under
the [Unsplash license](https://unsplash.com/license).

---

## Build environment and known environment traps

Recorded 2026-08-17 while bringing up a second build machine (`Pro-M4`) alongside
the original `Mac Studio`. Read this before building on any host that is not the
machine which produced the currently-installed APKs.

### The debug signing key is project state, not machine state

Android debug builds are signed with a per-machine auto-generated key at
`~/.android/debug.keystore`. **A second machine generates a different key, and its
APK cannot upgrade an install produced by the first machine** — `adb install -r`
fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

That is normally solved by uninstalling first. On the Fire tablet it is not; see
[the Fire uninstall block](#the-debug-key-and-the-uninstall-block).

The key that produced every APK in `artifacts/` and every build installed on the
Fire tablet has this certificate digest:

```text
11b408c4319ed4355ef3be99e770566b631bb4ff67eac67e421646a7cf2836f8
```

Verify a candidate keystore, and an APK, with:

```sh
keytool -list -v -keystore ~/.android/debug.keystore \
  -storepass android -alias androiddebugkey | grep -i "SHA256:"

apksigner verify --print-certs <apk> | grep -i "SHA-256 digest"
```

**Back up the Mac Studio's `~/.android/debug.keystore` alongside the production
keystore.** Losing it means no existing debug install on a policy-locked device
can ever be upgraded in place again. A copy made on 2026-08-17 lives at
`private/macstudio-debug.keystore` (git-ignored, on the NAS).

### `android/local.properties` is shared across machines via the NAS

`local.properties` is git-ignored but it is *not* per-machine here — the checkout
lives on the SMB share, so the last machine to write `sdk.dir` wins for everyone.
Building on the Mac Studio after Pro-M4 (or vice versa) fails until it is pointed
at that host's SDK again:

```sh
sed -i '' "s|^sdk.dir=.*|sdk.dir=$HOME/Library/Android/sdk|" android/local.properties
```

`sdk.dir` takes precedence over `ANDROID_HOME`, so setting the environment
variable alone does not help. Do this before the local-disk copy below.

### Gradle cannot write its build directory to the SMB share

The storage section above notes Gradle's *cache* cannot live on the NAS. The
per-project `build/` directory has the same limitation, and
`scripts/gradle-local.sh` relocates only the cache. A build against the NAS
checkout therefore fails late, at packaging:

```text
Execution failed for task ':app:packageDebug'.
> Unable to delete directory '.../build/intermediates/incremental/packageDebug/tmp'
```

macOS SMB then also refuses `rm -rf` on that directory with `Directory not
empty`. Compile, unit tests, and lint all pass first, so this reads as a build
failure when it is really a filesystem failure.

Build from a local-disk copy instead. Only `android/` and
`node_modules/@capacitor` are needed — about 25 MB:

```sh
LOCAL=/tmp/op-build
mkdir -p "$LOCAL/node_modules"
rsync -a --exclude 'build/' --exclude '.gradle/' android "$LOCAL/"
rsync -a node_modules/@capacitor "$LOCAL/node_modules/"
gradle -p "$LOCAL/android" --project-cache-dir /tmp/gradle-pc \
  --no-daemon :app:assembleDebug
```

Run `npm run build && npx cap sync android` on the NAS checkout *first*, so the
web bundle is already staged in `android/app/src/main/assets/public`. The
alternative is a Gradle init script that sets `project.layout.buildDirectory` to
a local path, passed with `-I`.

`gradle-local.sh` deletes its cache on exit unless `OPENPANEL_KEEP_LOCAL_CACHE=1`,
so an interrupted build re-downloads the whole dependency graph next time.

### Google's Maven repository can be blocked by the site's own network policy

Every AndroidX, Capacitor, Media3, and Readium artifact comes from
`dl.google.com/dl/android/maven2`, and the Android SDK installs from the same
host. On the deployment LAN this host was intermittently unreachable over both
IPv4 and IPv6 while `www.google.com` stayed up — which presents as Gradle hanging
on dependency resolution rather than as an obvious network error.

The cause was a UniFi Traffic Rule, not a Google outage. See
[UniFi domain rules and Google shared-IP collateral](#unifi-domain-rules-and-google-shared-ip-collateral).
Diagnose with a direct fetch rather than trusting Gradle's error text:

```sh
curl -s -o /dev/null -w "%{http_code}\n" \
  "https://dl.google.com/dl/android/maven2/androidx/core/core/1.17.0/core-1.17.0.pom"
```

`200` is healthy. `000` is a silent drop, not a refusal.

### Bootstrapping a bare machine

A host with no Android tooling needs, in order: Google `platform-tools` (adb),
`commandlinetools`, then `sdkmanager --install "platform-tools"
"platforms;android-36" "build-tools;36.0.0"`, plus Node 22 and JDK 21 (Java 17
fails Capacitor's source level 21).

Two traps: `sdkmanager --licenses` hangs when stdin is not a live terminal —
write the license-hash files into `$ANDROID_HOME/licenses/` directly instead. And
`android/local.properties` is git-ignored, so it still holds the previous
machine's `sdk.dir` and must be repointed.

`npm run verify:versions` fails when `package.json` and
`android/app/build.gradle` disagree. They had drifted to 1.1.37 vs 1.1.47 and
were reconciled to 1.1.47 on 2026-08-17.

---

## One fix, every build — the cross-build cadence

There is **one codebase and one web bundle**; "the Fire build", "the TV build",
and "the ArborXR build" are the same source shipped through different doors. A
fix landed on one device is not done until it has gone through every door. This
section is the checklist that makes that automatic. It exists because on
2026-08-17 the YouTube channel-art fix was verified on the Fire while the Google
TV, the sideloaded onn tablet, and the whole ArborXR fleet were still running the
broken code — none of them were "wrong", they were just not on the list.

### The build matrix

| Target | Package / key | How it gets a build | Where it is checked |
|---|---|---|---|
| Fire 7 kiosk (`GR71WE05531501MX`) | `com.orgista.openpanel.debug`, Mac Studio debug key `11b408c4…` | `adb install -r` of the debug APK, **only** from a machine holding that key (see [the Fire uninstall block](#the-debug-key-and-the-uninstall-block)) | `adb` row |
| Google TV (`192.168.1.189`) | `com.orgista.openpanel.debug`, same debug key | `adb install -r` of the same debug APK | `adb` row |
| Sideloaded production tablets (e.g. onn 11 Pro `ONN11PRO00156016`, Device Owner) | `com.orgista.openpanel`, production key `94323ad0…` | `adb install -r` of the signed release APK | `adb` row |
| ArborXR fleet (Kids / Boys / Music groups, Pink TCL canary) | `com.orgista.openpanel`, production key | `scripts/deploy-arborxr-beta.sh` (upload → Debug on Pink TCL → promote Production) | `arborxr` rows + channel table |

Anything that answers `adb devices` or is enrolled in ArborXR appears in the
table automatically; nothing has to be remembered.

### The checklist

1. **Classify the fix.** Web bundle (`src/`, anything under `src/app`) → every
   target above is affected, full stop. Native Android (`android/app/src/main`) →
   every target is affected unless the code is inside a platform guard
   (`isFireOs`, `isTelevision`, `FEATURE_LEANBACK`, `Build.MANUFACTURER`); if it
   is guarded, say which targets in the commit message and still rebuild all of
   them, because the manifest and the shared web bundle change regardless.
2. **Bump once, build both flavours.** `package.json` + `android/app/build.gradle`
   move together (`npm run verify:versions`). Build **debug and release from the
   same tree** in the same sitting — `assembleDebug` for the Fire/TV door,
   `scripts/build-release-keychain.sh` for the production door — so no target is
   ever more than one build behind. Copy both to `artifacts/openpanel-<ver>-{debug,release}.apk`
   with a `.sha256`.
3. **Ship every door, canary first.**
   - Fire: `adb install -r`, then verify the specific fix on the device (screenshot
     or a DevTools/DOM check — the channel-art bug looked fine in the data and was
     only visible in the rendered tiles).
   - TV and any sideloaded tablet on adb: `adb install -r` the matching flavour.
   - ArborXR: `deploy-arborxr-beta.sh test-candidate` (Pink TCL only) →
     `deploy-production`. If its assignment audit refuses, fix the assignment; do
     not work around the gate.
4. **Prove it with the table.** Run

   ```sh
   scripts/openpanel-fleet-versions.sh
   ```

   Every row must read `current`. A `BEHIND` row is an unshipped door; an
   `(offline)` row is a device that will pick the build up on its own and should
   be re-checked later. Paste the table into the handoff / commit.
5. **Record it in the relevant device section** of this README (Fire / TV /
   ArborXR) — what changed and the version that carries it — so the next person
   reading only that section sees the current state.

### Why the two flavours must move together

The debug package and the production package are *different apps* on the device
(different package names, different signing keys), so a device can hold both and
they never update each other. Kairo and Osias currently carry an old
`com.orgista.openpanel.debug` (1.1.39) next to the managed production install; it
is harmless but it is not the fix. When checking a device, check the package that
is actually the launcher there.

### Fleet state at the time of writing (2026-08-17)

`scripts/openpanel-fleet-versions.sh` output after the channel-art fix:

- `adb`: Fire 7 `1.1.48-debug` current · Google TV `1.1.48-debug` current ·
  onn 11 Pro `1.1.48` current.
- `arborxr`: Public TCL, Pink TCL, Kairo, Osias on `1.1.45` (**BEHIND**); Samsung
  A9+ and ONN (Ontario) `pending-install`, offline for weeks. Build `1.1.48`
  (code 57, SHA-256 `c264f5aa…`) is uploaded and `available` in ArborXR but no
  channel points at it yet — the guarded deploy stopped because **Kids and Boys
  are assigned the Debug channel** (Debug is meant to be Pink TCL only; Music is
  the only group on Production). Restore Kids/Boys → Production, then run
  `deploy-arborxr-beta.sh test-candidate` and `deploy-production`; or, knowingly,
  pin Debug and Production to the 1.1.48 build directly, accepting that Kids/Boys
  update without the Pink TCL canary.

---

## Google TV and Android TV

## OpenPanel — Google TV & Android TV

Television-specific configuration, deployment guidance, and behavioural
differences from mobile devices.

Shared product overview, build, signing, storage, security, repository policies,
and license/attribution notices live in
[the mobile section](#mobile-tablet-and-xr). Fire OS behaviour lives in
[the Fire OS section](#amazon-fire-os).

- **Package:** `com.orgista.openpanel`
- **TV surface:** `LEANBACK_LAUNCHER` category plus a 16:9 TV banner
  (`@drawable/openpanel_tv_banner`), declared in the same single APK as the
  phone/tablet `LAUNCHER` entry.
- **Optional hardware:** `android.software.leanback`,
  `android.hardware.touchscreen`, and `android.hardware.faketouch` are all
  declared `required="false"` so one APK installs across TV, tablet, and XR.

### Android 17 and Google TV readiness

OpenPanel uses the latest stable Capacitor 8 Android toolchain: compile SDK 36,
target SDK 36, and minimum SDK 24. The manifest does not set `maxSdkVersion`, so
the APK remains installable on Android 17/API 37 devices. Android 17 is still a
beta platform as of July 2026, so the production build keeps the stable API 36
target while its all-app behaviour changes are reviewed and tested. Target SDK 37
should be adopted only after Android 17 and the supporting Capacitor/Android
Gradle toolchain are stable; prerelease framework dependencies are intentionally
excluded from production.

Current readiness work includes:

- adaptive tablet and TV layouts in landscape and portrait;
- launcher and Leanback launcher declarations in one APK;
- a 16:9 TV banner and optional touchscreen/faketouch hardware;
- Google TV/D-pad controller detection and system speech recognition;
- a keyboard-free PIN keypad with explicit D-pad focus movement;
- API-key-free exact YouTube channel verification using unique handles and
  canonical channel IDs, plus a recent-video channel browser and direct video and
  playlist URLs;
- D-pad-visible focus states and a close-first restricted YouTube player;
- keep-screen-awake behaviour only while YouTube is open;
- no bundled native shared libraries, avoiding native 64-bit/16 KB page-size
  compatibility risks;
- backup disabled and no maximum supported Android version.

Before raising `targetSdkVersion` to 37, repeat the web interaction suite,
Android lint/unit tests, on-device instrumentation, Google TV D-pad navigation,
ArborXR lock-task behaviour, signed upgrade verification, and a full API 37
emulator or physical-device pass. The current workstation has the API 37.1
platform files but not an Android 17 runtime image, so installability is
build-reviewed rather than claimed as physical API 37 validation.

### Google TV app art and home-screen rendering

Measured on the TCL "Smart TV" (Android 9, 1920x1080, `densityDpi=320`) on
2026-08-18. The WebView reports a **960x540 CSS viewport at devicePixelRatio 2**,
so every CSS pixel on that panel is two device pixels — the single most
important number when reasoning about how sharp anything looks there.

**Icon resolution.** `getApplicationIcon()` resolves against the display
density, so on this TV it returns the **xhdpi** icon — 96x96 for a typical app
(verified by unzipping Disney+: `mipmap-xhdpi` 96px, `mipmap-xxxhdpi` 192px,
adaptive foreground 432px). The old pipeline then rasterised that to a fixed
144px, i.e. it *upscaled* before the WebView downscaled again, and an upscale is
unrecoverable blur. `SystemBridgePlugin.drawableForDensity()` now asks for
`DENSITY_XXXHIGH` and walks down, and `drawableToBase64()` fits the drawable
into its target box **without ever enlarging past the intrinsic size**.

**Banners.** Apps that appear in the stock Google TV launcher ship a Leanback
banner (320x180dp; 512x288 at xhdpi). `getInstalledApps()` now returns it as
`banner`, and the home row uses it as the tile art, which is what the stock
launcher shows. Two rules learned the hard way:

- Banner art is **tile-only**. Stretching a 512x288 banner across the 1920px
  hero upscales it ~6x and looks far worse than the gradient + icon treatment.
  `ContentItem.bannerUrl` is deliberately separate from `imageUrl` for this.
- A tile showing a banner prints **no title label** — the banner already carries
  the wordmark, and overlaying the app name double-prints the brand and covers
  art (Pluto TV's tagline was the giveaway).

**Home-screen smoothness.** The TV idles at a clean 60fps; all jank came from
what happens when selection changes. Isolated by measurement, not guesswork:

| Change | p95 frame | worst frame |
| --- | --- | --- |
| Baseline (1.1.52) | 150ms | 250ms |
| After all fixes (1.1.56), synthetic scrub | 67ms | 117ms |
| After all fixes, real D-pad presses | **26ms** | 100ms |

What actually mattered, in order:

1. **The hero is the expensive thing.** Hiding it alone took the p95 from 138ms
   to 50ms. It is a full-width 1920x624 surface with two gradient overlays.
2. **Do not repaint it while the user scrubs.** `useSettledValue(featured, 220)`
   holds the hero until focus stops moving — the stock Google TV home behaves
   the same way, with the highlight moving instantly and the detail area
   catching up. `useDeferredValue` was tried first and was not enough.
3. **Promote the animating layers.** `will-change: opacity` on the hero's
   cross-fading art and `contain: layout paint` on the hero itself halved the
   p95 on their own; `will-change: transform` on the tile does the same for the
   512x288 banner bitmap it now carries.
4. **`decoding="async"` on every `ArtImage`** — without it the WebView decodes
   tile bitmaps synchronously during paint.
5. React work was *not* the bottleneck. Memoising `ContentTile` and stabilising
   the row callbacks is still correct (script time per focus change fell), but
   on its own it moved the frame numbers very little. Measure before optimising
   React on this device.

Do not "fix" the hero by re-adding an `AnimatePresence` keyed on the item id.
That remounts the whole subtree on every D-pad step, which also destroys the
focused Open button and drops focus to `<body>`.

### Google TV input behaviour

- OpenPanel detects television UI mode, Leanback support, touch availability,
  attached D-pad/game controllers, alphabetic keyboards, and the active remote
  device name when Android exposes it.
- PIN creation and entry use OpenPanel's on-screen numeric keypad, so Gboard no
  longer covers or resizes the PIN card. D-pad arrows move predictably between
  keys; OK selects; hardware number keys also work.
- The YouTube channel field is declared as a search input, allowing Google TV
  Gboard to select its TV search layout and built-in speech-to-text.
- The microphone button invokes Android's system speech recognizer and fills the
  channel field with the result. OpenPanel does not request direct microphone
  permission.
- Some ArborXR lock-task policies block the recognizer's separate system Activity
  even when Android reports it as installed. OpenPanel falls back to focusing the
  channel field and tells the administrator to press the Google TV remote's
  microphone button, which uses the supported TV keyboard/Gboard dictation path.
- Recovery-answer and Wi-Fi fields no longer force Gboard to open immediately on
  a remote-driven device. The keyboard opens only when the user selects the field.

#### Remote Back handling

`MainActivity` installs an `OnBackPressedCallback` that dispatches a synthetic
`Escape` `keydown` into the WebView, targeting the highest-`z-index` visible
element carrying `data-dpad-scope`. This lets the remote's Back button close the
topmost modal/overlay rather than exiting the launcher.

### Kiosk and exit-kiosk on TV

Google TV is a non-Fire device, so `KioskState.canReliablyStartLockTask()`
returns `true` and OpenPanel uses Android's real lock-task path rather than the
Fire redirect fallback.

- **Enter kiosk:** in standalone mode with kiosk enabled,
  `MainActivity.pinKioskIfUnlocked()` runs on every window focus gain. As Device
  Owner it first calls `KioskLock.applyDeviceOwnerLockdown(...)` to allow-list
  OpenPanel and hide the status bar, so `startLockTask()` enters silent `LOCKED`
  mode instead of showing Android's "App is pinned" confirmation dialog. Without
  Device Owner it falls back to ordinary user-confirmed screen pinning.
- **Exit kiosk:** the Admin Panel → Kiosk tab disables the
  `.OpenPanelHomeActivity` HOME activity-alias. Because HOME lives on a
  separately-toggleable alias, Android immediately falls back to the OEM
  launcher while OpenPanel stays available from the app drawer. The lock task is
  stopped alongside it.
- **Leaving to a child app:** `SystemBridgePlugin` unpins before it deliberately
  starts another activity (launching an app or a settings screen), then
  `pinKioskIfUnlocked()` re-engages on the next focus gain when the user returns.

### TV DNS filtering

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

Some Android 9 TV firmware honors personalDNSfilter's phone orientation while the
system consent activity is open. On the tested TCL this rotates the whole
display, starts the ambient screen, and makes remote focus unreliable. For that
reason OpenPanel reports the filter state but does not launch its phone UI from
the TV Admin panel. Provision the one-time approval through the deployment
workflow, then return to OpenPanel and verify **Filter VPN: Connected**.

#### Recommended TV profile

Use a small, predictable configuration:

- upstream: AdGuard filtered DNS over HTTPS, with its filtered UDP resolvers as
  fallback;
- primary list: 1Hosts Lite only, refreshed every seven days;
- local overrides: `assets/dns/tv-additional-hosts.txt`;
- traffic logging: off after validation;
- Private DNS: off while the local DNS VPN is active;
- always-on VPN: on;
- VPN lockdown: off unless the fleet administrator has an independently tested
  recovery path.

The local override file is intentionally short. Do not block broad domains such
as `google.com`, `googleapis.com`, `youtube.com`, `googlevideo.com`, `tcl.com`,
Amazon Web Services, CloudFront, or Akamai. Those domains share streaming,
authentication, update, and CDN infrastructure. Validate YouTube, Netflix, OS
updates, captive-portal detection, and device management after every list change.

DNS filtering can reduce background requests and data transfer. It is not a
guaranteed speed boost: a slow resolver or oversized list can increase latency,
and DNS cannot stop traffic sent to hard-coded IP addresses or an app's own
encrypted resolver.

#### Management modes

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
not override the active DPC. Deploy the filter as its own managed application and
configure always-on VPN through the management policy or the TV's VPN settings.
Do not configure both a local DNS VPN and a strict Private DNS host unless the
resolver is reachable through and outside the VPN.

#### Recovery

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

### UniFi domain rules and Google shared-IP collateral

Site-network context, diagnosed 2026-08-17. This is not an OpenPanel defect, but
it breaks OpenPanel builds and device provisioning, and the same trap applies to
the DNS guidance above.

The site's UniFi gateway runs a managed Traffic Rule that blocks YouTube by
**domain**. UniFi resolves each configured domain to IP addresses and blocks by
address — and Google serves much of its estate from shared front-end IPs. On the
day this was diagnosed:

```text
dl.google.com         →  142.250.100.91, .93, .136, .190
music.youtube.com     →  142.250.100.91, .93, .136, .190   (identical)
youtube-nocookie.com  →  142.250.100.91, .93, .136, .190   (identical)
youtu.be              →  142.250.100.91, .93, .136, .190   (identical)
www.google.com        →  142.251.157.119                   (different, unaffected)
```

Blocking `youtu.be` therefore also blocked `dl.google.com` — the Android SDK and
Google Maven host — for every client targeted by the rule. Because a cron
refresher re-resolved the domains every minute and Google rotates which IPs a
hostname returns, the breakage was intermittent: large downloads would sometimes
succeed and then fail minutes later.

Consequences worth knowing:

- **There is no way to allow `dl.google.com` while blocking `youtu.be` by
  domain**, because they share addresses. Removing the real YouTube domains would
  defeat the policy.
- The workable fix is a **per-client exemption** (`exclude_macs`) for any machine
  that must reach Google infrastructure — build hosts, provisioning laptops.
- Clients using **randomized/private MAC addresses** silently lose their
  exemption when the MAC rotates. Prefer a stable per-network MAC on any build or
  admin machine.
- The Fire tablet was never affected because it already carried an exemption.
- The DPI **app-ID** rule blocks YouTube without this side effect; domain rules
  on shared-CDN hostnames are what cause collateral.

The same reasoning underlies the warning in the DNS profile above against
blocking broad domains such as `google.com`, `googleapis.com`, or
`googlevideo.com` — those hostnames share streaming, authentication, update, and
CDN infrastructure.

### TV debloat helper

`scripts/tv-debloat-adb.sh <device-serial> [preview|apply|restore]` mirrors
OpenPanel's reviewed TCL/generic TV policy. It refuses to run against a device
that does not report `android.software.leanback`, and it operates on an exact
package list only — deliberately excluding Settings, WebView, Play services,
launchers, TV input, HDMI, Wi-Fi, Bluetooth, OTA, package installation, and ADB
services.

```sh
./scripts/tv-debloat-adb.sh 192.168.1.189:5555 preview   # list eligible packages
./scripts/tv-debloat-adb.sh 192.168.1.189:5555 apply     # pm uninstall --user 0
./scripts/tv-debloat-adb.sh 192.168.1.189:5555 restore   # cmd package install-existing
```

`apply` removes packages for user 0 only; `restore` reinstalls the existing
system copies. Nothing touches the read-only system partition.

### YouTube channel verification

OpenPanel does not require a YouTube API key. In **Admin Panel → YouTube**, an
administrator can enter a channel name, unique `@handle`, channel ID, or channel
URL. The native Android bridge:

1. normalizes the entry into an exact YouTube channel URL;
2. downloads that public channel page;
3. requires a canonical `UC…` channel ID in the response;
4. extracts the channel title and artwork; and
5. returns the canonical `youtube.com/channel/UC…` URL to the Admin Panel.

The **Add** button appears only after verification succeeds. Invalid and missing
handles are rejected instead of creating a broken launcher tile.

YouTube channel display names are not unique, while handles are unique. A plain
name such as `Google Developers` is therefore tried as the exact handle
`@GoogleDevelopers`. If a channel's display name and handle differ, enter the
unique `@handle`. This intentionally avoids scraping YouTube's general search
results.

Video and playlist URLs can still be pasted directly. No credential is created,
requested, stored, or shipped in the APK.

#### Browsing an approved channel

Opening a verified channel tile does not start an arbitrary or automatically
selected video. OpenPanel requests YouTube's public Atom feed for the approved
canonical channel ID, validates the returned channel and video IDs in the native
Android bridge, and shows the channel's recent uploads as a D-pad- and
touch-friendly grid. The user chooses a video, which then opens in the same
restricted player used for individually approved video links.

This is intentionally not general YouTube search: the browser exposes only recent
uploads from the channel that an administrator approved. Older handle-only
entries that predate canonical-ID verification must be removed and added again
before the recent-video browser can load them.

### Child-safe boundary on TV

The child-facing catalog policy is identical across Fire OS, generic Android
tablets, Google TV, and XR — see
[the mobile section](#child-safe-app-and-browser-boundary)
for the package policy and the Kiddle-only Safe Browser.

### Device validation record — Pink TCL, 2026-07-28

Kiosk exit-and-return pass on the ungrouped ArborXR test device. Screenshots and
compressed logs for this run remain under
`artifacts/device-tests/pink-tcl-2026-07-28-app-exit/` (git-ignored).

- Device: TCL 9183W, Android 12
- Production: OpenPanel 1.1.24 (`com.orgista.openpanel`)
- Local test build: 1.1.26-debug, code 35 (`com.orgista.openpanel.debug`)
- ArborXR lock task remained `LOCKED`; no device data was cleared.

Twelve assigned apps launched during the exit pass. Android Home returned to
OpenPanel for all twelve; individual Back-button behaviour remains app-specific.

Bloons TD 6 version 55.2 launches from its OpenPanel tile and reaches the start
screen. Earlier runs captured an intermittent Google Play Licensing
`NullPointerException` inside Bloons; OpenPanel itself did not reject or crash
the launch.

The final bottom-swipe test started at the far-left edge in Bloons and returned
directly to production OpenPanel. The accessibility service initiated the
activity as its own UID, ArborXR's kiosk launch activity did not appear, and the
visible handle is a compact 72 × 4 dp pill near the bottom edge.

### Deployment

Google TV is a compatible non-Fire target, so it uses the standard ArborXR
companion flow or the standalone Device Owner flow documented in
[the mobile section](#deploy--arborxr). Device Owner
provisioning commands are in
[the mobile section](#managed-android-provisioning-device-owner).

**Live TV state (2026-08-17):** the Google TV at `192.168.1.189` (TCL "Smart TV",
Android 9, device `BeyondTV`) is *not* ArborXR-managed; it runs
`com.orgista.openpanel.debug` signed with the Mac Studio debug key, upgraded over
adb from 1.1.47-debug to **1.1.48-debug** (channel-art fix) in place. It is *not*
Device Owner (`dumpsys device_policy` lists no admins), so kiosk on this TV is
plain "OpenPanel owns HOME" and is **adb-assisted in both directions**:

- **Kiosk was armed by hand** — `pm disable-user --user 0
  com.google.android.tvlauncher` (the stock launcher's HOME filter is
  `priority=2`, so a third-party HOME alias can never out-rank it) plus the
  OpenPanel HOME alias enabled. `tvlauncher` is in `DebloatCatalog`'s
  `PROTECTED_PACKAGES`, so OpenPanel never disables it itself.
- **Exit Kiosk (Admin Panel) refuses instead of stranding on a black screen.**
  `exitKioskToSystemHome` first asks whether *any* non-fallback HOME resolver
  other than OpenPanel would actually win HOME
  (`findSystemHomeComponent`/`selectSystemHomeComponent`, which iterate every
  `queryIntentActivities` candidate, skip OpenPanel, skip anything matching the
  `FallbackHome` prefix convention, and prefer a system launcher but accept a
  non-system one). If a safe launcher exists, the alias is disabled and HOME is
  fired normally. If none does — e.g. `tvlauncher` was `pm disable-user`'d and
  no other launcher is installed — the call resolves with `exited:false`,
  `reason:"NO_SAFE_LAUNCHER"`, and a `recoveryCommand` string
  (`adb shell pm enable <disabled launcher package>`, derived by re-querying
  HOME resolvers with `MATCH_DISABLED_COMPONENTS` to find the specific
  currently-disabled non-fallback launcher — falling back to a generic
  placeholder only when none can be identified). `kioskEnabled` is left `true`
  on refusal (screen pinning is stopped so OpenPanel itself stays navigable
  while the operator runs the adb command). This TV was previously stranded on
  `com.tcl.keycustomfunctionservice/.FallbackHome` — a **black screen** that
  survived a power cycle, since TCL's firmware also blocks `BOOT_COMPLETED` for
  third-party apps (`prevent third party app from boot complete broadcast` in
  logcat, so `KioskBootReceiver` never runs on this TV either) — that incident
  is what this refusal path exists to prevent. Seen live 2026-08-17; fixed and
  re-verified live in the Rev 2 pass (see `private/tcl-tv-handoff/stage4-sonnet-impl.md`
  → "Rev 2").
- **Admin gate reset (no known PIN):** the admin actions above (including
  `exitKioskToSystemHome`) require an `adminToken` once a PIN has been set,
  checked natively against `kiosk_admin_verifier` in
  `shared_prefs/openpanel.device_health.v1.xml` (readable via
  `adb shell run-as com.orgista.openpanel.debug cat shared_prefs/openpanel.device_health.v1.xml`).
  If the PIN itself is unknown, use the on-screen **Forgot PIN?** flow (answers
  the security question, then lets you set a new PIN) rather than clearing the
  verifier — that keeps the record consistent end to end. As a last-resort
  escape hatch when the security answer is also unknown, `run-as` and remove
  the `kiosk_admin_verifier` key from that same prefs file; the native gate
  fails open only until a verifier is stored again, then only a matching token
  authorizes the gated calls.
- **Recovery / finishing Exit Kiosk:** `adb shell pm enable
  com.google.android.tvlauncher`, then HOME resolves to
  `com.google.android.tvlauncher/.MainActivity` and OpenPanel stays launchable
  from the Apps row (`MainActivity` keeps its `LEANBACK_LAUNCHER` entry).
- **Re-arming kiosk:** `adb shell pm disable-user --user 0
  com.google.android.tvlauncher` and `adb shell pm enable
  com.orgista.openpanel.debug/com.orgista.openpanel.OpenPanelHomeActivity`,
  then re-enable kiosk in the Admin Panel. The durable fix is to provision the
  TV as Device Owner (standalone flow above) so OpenPanel can own HOME through
  `addPersistentPreferredActivity` and Exit Kiosk restores the launcher on its
  own.

Current state after the 2026-08-17 recovery: `tvlauncher` enabled, OpenPanel
HOME alias disabled, kiosk disarmed. It shows up in
`scripts/openpanel-fleet-versions.sh` as an `adb` row when its wireless ADB is
authorised (`adb connect 192.168.1.189:5555`).

---

## Amazon Fire OS

## OpenPanel — Amazon Fire OS

Fire-tablet-specific configuration, kiosk behaviour, and verification profile.

Shared product overview, build, signing, storage, security, repository policies,
and license/attribution notices live in
[the mobile section](#mobile-tablet-and-xr). Google TV behaviour lives in
[the Google TV section](#google-tv-and-android-tv).

### Verified device and build

OpenPanel has a physical-device verification profile for the Amazon Fire 7
(2022, 12th Generation). Amazon identifies this tablet by model `KFQUWI`; the
Android device, product, and build codename is `quartz`. See Amazon's
[Fire tablet identification guide](https://developer.amazon.com/docs/device-specs/ft-identify-tablet-devices.html).

| Field | Verified value |
| --- | --- |
| Tablet | Amazon Fire 7 (2022, 12th Generation) |
| Model / codename | `KFQUWI` / `quartz` |
| Fire OS | Fire OS 8, build `RS8338.3339N` |
| Android base | Android 11, API 30 |
| Physical display | 600×1024 at 160 dpi |
| OpenPanel orientation | Reverse landscape (`user_rotation=3`) |
| OpenPanel app viewport | 1024×552 after Fire OS's 48 px navigation inset |
| Verified OpenPanel build | 1.1.37-debug, version code 46 |
| Local artifact name | `openpanel-1.1.37-fire-kids-kiosk-debug.apk` |
| Verification date | 2026-08-14 |

### UI verification

The Settings Wi-Fi and Bluetooth pages and all eight Admin Panel tabs were
checked on the physical device: Applications, Device Health, Books & Audio,
YouTube, Screen Saver, Kiosk Lock, Bug Log, and Security. At the Fire 7's usable
height, the modal reserves the Fire OS navigation inset, keeps all Admin tabs on
one line, preserves 40 px touch targets, and scrolls panel content independently
without moving the tab bar. The Settings admin footer remains fully visible.

Fire OS can reserve the bottom 48 px even when CSS reports the full 600 px
physical height. Responsive modal rules must account for that inset; testing at
an ordinary 1024×600 desktop viewport alone is not sufficient.

### Fire OS management limits

The Fire profile always selects standalone mode and does not show ArborXR or
Device Owner enrollment. OpenPanel uses the controls available to a normal Fire
app: launcher/Home redirection through accessibility, device admin where Fire OS
permits it, notification-listener policy, overlay and usage access, system-UI
protection, and a foreground-return loop. Enabling **Fire kiosk** records this
redirect kiosk state without triggering Fire OS's unreliable screen-pinning
prompt.

This is intentionally described as a Fire standalone kiosk rather than Android
Device Owner. Managed Android provisioning remains available for compatible
non-Fire devices and is documented in
[the mobile section](#managed-android-provisioning-device-owner).
The target tablet does not generate an enrollment QR that would disappear during
a factory reset.

Fire builds force OpenPanel into standalone mode. They use the protected Amazon
launcher plus OpenPanel's accessibility Home redirect, device admin, notification
blocking, overlay/usage access, and reversible ADB configuration. The Admin Panel
intentionally contains no ArborXR selector, Device Owner status step, or
on-device enrollment QR.

---

### How the Fire kiosk actually works

Fire OS does not let a third-party app take the HOME role the way stock Android
does, and `KioskState.canReliablyStartLockTask(deviceOwner, fireDevice)` returns
`deviceOwner || !fireDevice`. On a non-Device-Owner Fire tablet that is **false**,
so `startLockTask()` is never called. The Fire kiosk is therefore a *redirect*
kiosk, not a lock-task kiosk.

The mechanism is:

1. `KioskState.normalizeModeForDevice()` forces `MODE_STANDALONE` on any device
   where `LandscapeOrientationLock.isFireDevice(MANUFACTURER, BRAND)` is true.
2. `HomeGestureAccessibilityService` observes `TYPE_WINDOW_STATE_CHANGED` and
   `TYPE_WINDOWS_CHANGED` events.
3. When the foreground window belongs to `com.amazon.firelauncher`,
   `FireLauncherRedirect.shouldRedirect(windowPackage, managementMode,
   openPanelHomeEnabled)` returns true and the service calls `returnToOpenPanel()`
   (debounced at 500 ms).
4. `SystemUiProtection` separately collapses the notification shade, and a
   top-edge accessibility overlay guards against pull-down.

`KioskState` persists `managementMode`, `kioskEnabled`, and `keyguardDisabled` in
the `openpanel.kiosk` SharedPreferences file, so the *intent* to be in kiosk
survives a reboot.

#### Audit finding — kiosk does not reliably re-arm after reboot

**Status: open defect as of 1.1.47 (versionCode 56).** Three gaps were found
while auditing the reboot path.

**1. There is no boot receiver at all.** The manifest declares no
`RECEIVE_BOOT_COMPLETED` permission and no `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED`
receiver, and no source file references either action. Nothing starts OpenPanel
when the tablet finishes booting.

*Confirmed on hardware (2026-08-17, serial `GR71WE05531501MX`, installed
1.1.37-debug / code 46):*

```console
$ adb shell dumpsys package com.orgista.openpanel.debug | grep -i BOOT_COMPLETED
                                     # (no output — permission not declared)

$ adb shell cmd package query-receivers \
    -a android.intent.action.BOOT_COMPLETED | grep -c packageName
118                                  # boot receivers registered on the device

$ adb shell cmd package query-receivers \
    -a android.intent.action.BOOT_COMPLETED | grep -ci openpanel
0                                    # none of them are OpenPanel
```

**2. The accessibility service does not evaluate the window that is already in
front when it connects.** `onServiceConnected()` calls
`LandscapeOrientationLock.enforce`, `KioskVolumePolicy.enforceTarget`,
`addHomeHandle()`, `addShadeGuard()`, and `syncShadeGuardVisibility(getPackageName())`
— note it passes OpenPanel's *own* package rather than the actual foreground
window — and it never calls `FireLauncherRedirect.shouldRedirect(...)`.

That is exactly the post-reboot situation: Fire OS boots straight into
`com.amazon.firelauncher`, and the launcher window settles *before* the
accessibility service binds. Because the redirect only ever fires from
`onAccessibilityEvent`, and no new window-state-change event occurs for a window
that is already stable and focused, the redirect never runs. The tablet sits on
the Amazon launcher until something else changes the foreground window.

**3. The Fire launcher package is a single hardcoded constant.**
`FireLauncherRedirect.FIRE_LAUNCHER_PACKAGE` is `"com.amazon.firelauncher"` only.
Fire OS uses different home packages across generations and OTA builds, and the
first-boot setup/OOBE activity is a different package again. Any Fire build whose
home package differs is not redirected at all. Confirm the live value with:

```sh
adb shell cmd package resolve-activity \
  -a android.intent.action.MAIN -c android.intent.category.HOME
```

(The `--brief` form alone returns "No activity found" on Fire OS 8 — the action
must be supplied.)

#### Live device state — audited 2026-08-17

Audited over ADB against the verified tablet (serial `GR71WE05531501MX`,
`KFQUWI`/`quartz`, Fire OS 8 `RS8338.3339N`, Android 11/API 30).

**Resolved HOME is `com.amazon.firelauncher/.Launcher`** (`isDefault=true`,
`enabled=true`), so the original single constant was correct *for this build*.
The broadened set is robustness, not a fix for this unit — but note that
`com.amazon.hedwig` and `com.amazon.tv.launcher` are both installed on this
tablet (`hedwig` currently `enabled=3`, disabled-by-user). An OTA that promotes
either to HOME would have silently defeated the old single-constant check.

Installed: `com.orgista.openpanel.debug` **1.1.37-debug (versionCode 46)** only —
the production package is not present.

Special-access grants were **already all in place**, so no ADB grant pass was
needed on this unit:

| Grant | State |
| --- | --- |
| `enabled_accessibility_services` | `…/HomeGestureAccessibilityService` present, `accessibility_enabled=1` |
| `enabled_notification_listeners` | `…/OpenPanelNotificationListenerService` present |
| `SYSTEM_ALERT_WINDOW` | allow |
| `GET_USAGE_STATS` | allow |
| `WRITE_SETTINGS` | allow |
| deviceidle whitelist | `com.orgista.openpanel.debug` present |

Persisted kiosk state (`/data/data/<pkg>/shared_prefs/openpanel.kiosk.xml`):

```xml
<boolean name="kioskEnabled" value="false" />
<string name="managementMode">standalone</string>
<boolean name="keyguardDisabled" value="false" />
```

**Kiosk is currently disarmed on this tablet**, which is why the Amazon launcher
owns HOME. `KioskBootReceiver` correctly does nothing in this state — kiosk must
be enabled before any reboot test is meaningful.

#### Fix applied — not yet verified on hardware

All three gaps have been addressed in the working tree. **This code has not been
compiled or run**: the build host had no Android SDK at the time (see the note at
the end of this section), so treat it as reviewed-but-unverified until it is
built and tested on the Fire 7.

**`KioskBootReceiver`** (new) — registered for `BOOT_COMPLETED` with the
`RECEIVE_BOOT_COMPLETED` permission. It re-arms the kiosk only when standalone
mode, `KioskState.isEnabled()`, and the HOME alias all agree, so **Exit Kiosk
still survives a reboot unchanged**. It is not direct-boot aware by design —
`KioskState` reads credential-encrypted preferences that do not exist before
first unlock, so `LOCKED_BOOT_COMPLETED` is deliberately not handled. Android 10+
blocks background activity starts; OpenPanel's kiosk already requires
`SYSTEM_ALERT_WINDOW`, which is the exemption that permits this start. If the
start is still refused, the failure is logged and the accessibility path below
recovers it.

**`HomeGestureAccessibilityService.onServiceConnected()`** now schedules a
foreground-window audit at 750 ms / 2.5 s / 6 s. Each pass reads
`getRootInActiveWindow()` and runs the same `shouldRedirect(...)` decision
against whatever is already in front, instead of waiting for an event that never
comes. The staggered retries cover both the window not being queryable the
instant the service binds and Fire OS re-showing its launcher as boot settles.
`onDestroy()` now clears all pending handler callbacks, not just the transition
policy.

**`FireLauncherRedirect`** now matches an exact-name *set* of Fire home/setup
packages rather than a single constant, and accepts an optional
`resolvedHomePackage` argument so an unrecognised Fire home is still caught.
That fallback is **Fire-only** — `foreignHomePackage()` returns null off Fire, so
generic Android keeps its existing behaviour and OpenPanel never bounces a user
off an OEM launcher that Android still treats as the default. Package matching
remains exact-name throughout, per the repository's no-wildcard policy.

Unit tests in `FireLauncherRedirectTest` cover the new home packages, the
resolved-home fallback, and the null-package edges.

**Still to do before this can be called fixed:**

1. `./scripts/gradle-local.sh :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
2. Install on the Fire 7, enable kiosk, reboot, and confirm OpenPanel returns
   without any touch input.
3. Confirm Exit Kiosk still leaves the Amazon launcher in place across a reboot.
4. Confirm the live home package with
   `adb shell cmd package resolve-activity -c android.intent.category.HOME --brief`
   and add it to the exact-name set if it is not already there.

#### Audit finding — "auto-grant all necessary features" is not possible in-app

`DeviceAccess` documents this boundary directly: the grants the Fully-style kiosk
depends on are per-app *app-ops* and secure settings that **no** app can flip for
itself, and that a Device Owner cannot flip either through a public API. They are:

| Grant | Read by | Can the app grant it? |
| --- | --- | --- |
| Accessibility service | `isAccessibilityServiceEnabled` | No — `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` |
| Notification listener | `isNotificationListenerEnabled` | No — `enabled_notification_listeners` |
| Draw over other apps | `canDrawOverlays` | No — `SYSTEM_ALERT_WINDOW` app-op |
| Usage access | `hasUsageAccess` | No — `GET_USAGE_STATS` app-op |
| Write settings | `canWriteSettings` | No — `WRITE_SETTINGS` app-op |
| Ignore battery optimization | `isIgnoringBatteryOptimizations` | No — user confirmation |

OpenPanel's UI correctly reads each state and deep-links to the right Settings
screen. On a Fire tablet without Device Owner there is no in-app path to grant
them, so a fully hands-off first boot is not achievable from application code
alone. The supported way to pre-grant them is a one-time ADB provisioning pass
from an authorized workstation (below).

---

### ADB provisioning

Fire OS clears ADB-over-TCP on every reboot, so re-enable it from USB when
needed:

```sh
adb devices                 # confirm the tablet over USB first
adb tcpip 5555
adb connect 192.168.1.193:5555
```

Always confirm you are pointed at the right device before applying anything —
these commands are destructive to kiosk state if aimed at the wrong tablet:

```sh
adb -s "$ADB_SERIAL" shell getprop ro.product.model     # expect KFQUWI
adb -s "$ADB_SERIAL" shell getprop ro.serialno
```

#### One-time special-access grants

These are the grants that cannot be made from inside the app. Use
`com.orgista.openpanel.debug` for a debug build.

```sh
PKG=com.orgista.openpanel

adb -s "$ADB_SERIAL" shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb -s "$ADB_SERIAL" shell appops set $PKG GET_USAGE_STATS allow
adb -s "$ADB_SERIAL" shell appops set $PKG WRITE_SETTINGS allow
adb -s "$ADB_SERIAL" shell dumpsys deviceidle whitelist +$PKG

adb -s "$ADB_SERIAL" shell settings put secure enabled_accessibility_services \
  $PKG/com.orgista.openpanel.HomeGestureAccessibilityService
adb -s "$ADB_SERIAL" shell settings put secure accessibility_enabled 1
adb -s "$ADB_SERIAL" shell cmd notification allow_listener \
  $PKG/com.orgista.openpanel.OpenPanelNotificationListenerService
```

Verify each one afterwards rather than assuming success:

```sh
adb -s "$ADB_SERIAL" shell settings get secure enabled_accessibility_services
adb -s "$ADB_SERIAL" shell settings get secure enabled_notification_listeners
adb -s "$ADB_SERIAL" shell appops get $PKG
```

Note that `settings put secure enabled_accessibility_services` **overwrites** the
whole colon-separated list. Read the existing value first and append to it if any
other accessibility service must stay enabled.

#### Game display compatibility

Some game APKs declare their launcher activity as non-resizable. Fire OS 8 then
uses Android size-compatibility mode when the tablet is locked to landscape,
rendering the game in a roughly 600×351 window with large black borders. Apply
the following after confirming ADB is pointed at model `KFQUWI`:

```sh
adb -s "$ADB_SERIAL" shell settings put global force_resizable_activities 1
adb -s "$ADB_SERIAL" shell wm set-user-rotation lock 3
adb -s "$ADB_SERIAL" shell wm set-fix-to-user-rotation disabled
```

Confirm the effective settings with:

```sh
adb -s "$ADB_SERIAL" shell settings get global force_resizable_activities
adb -s "$ADB_SERIAL" shell dumpsys window displays
```

The resizable-activity override lets games use the full display instead of a
small size-compatibility window. Disabling fixed-to-user rotation allows a
portrait-only game to rotate into the full 600×1024 portrait display. OpenPanel
and landscape-native games return to reverse landscape. Do not patch and re-sign
third-party APKs to change their declared orientation.

#### Reversible device cleanup

On an ADB-authorized Fire tablet, exact packages may be disabled for user 0 with
`pm disable-user --user 0 PACKAGE` and restored with `pm enable PACKAGE`. Never
disable packages outside the reviewed catalog or the protected system boundary
described in
[the mobile section](#device-health-and-debloat-policy).

### Child-safe boundary on Fire

The child-facing catalog policy is identical across Fire OS, generic Android
tablets, Google TV, and XR — see
[the mobile section](#child-safe-app-and-browser-boundary)
for the package policy and the Kiddle-only Safe Browser. The Fire Silk components
are part of the reviewed reversible-hide catalog.

### Build and publication policy

Use the source build commands in
[the mobile section](#build). Debug APKs are local test
artifacts and remain ignored by Git. Signed APKs must come from the protected
release workflow so their package name, version, SDK range, and signing identity
are reproducible and verifiable. Fire tablets are not ArborXR deployment targets
in this project.

---

## Fire tablet handoff — open work as of 2026-08-17

State of the Fire 7 (`KFQUWI`/`quartz`, serial `GR71WE05531501MX`) and what
remains. Written for whoever picks this up on the Mac Studio.

### Device state right now

- **Installed: `com.orgista.openpanel.debug` 1.1.48-debug (versionCode 57)** —
  upgraded in place on 2026-08-17, first from 1.1.37 (code 46) to 1.1.47 (56),
  then to 1.1.48 (57) from the Mac Studio. Both upgrades used `adb install -r`
  with the Mac Studio debug key, so no data was lost: the media volume lock, the
  six Admin-configured YouTube channels, installed games, and Wi-Fi configuration
  all survived. The launcher icon is now the current quad mark (the tablet
  previously showed the older isometric-cube icon).
- **1.1.48 fixes YouTube channel art rendering as letter fallbacks.** The art was
  stored correctly (inline `data:` URLs, verified via WebView DevTools), but
  `ArtImage`'s retry path appended `?op_retry=N` to *every* src, and
  `data:…;base64,xxx?op_retry=1` is an invalid image. A stale retry timer armed
  for the pre-migration remote URL fired after the src switched to inline art,
  bumped the attempt counter, and cascaded all six tiles into the permanent
  letter fallback. Inline art is now never cache-busted, retry timers are
  cancelled on src change, and inline art skips retries entirely.
- **Kiosk is disarmed** — `kioskEnabled=false` in
  `/data/data/<pkg>/shared_prefs/openpanel.kiosk.xml`, so `com.amazon.firelauncher`
  owns HOME.
- **All six special-access grants are present** and survived the upgrade
  (accessibility, notification listener, `SYSTEM_ALERT_WINDOW`, `GET_USAGE_STATS`,
  `WRITE_SETTINGS`, battery whitelist). No ADB grant pass is needed unless the app
  is fully reinstalled.
- **`RECEIVE_BOOT_COMPLETED` is granted and `KioskBootReceiver` is registered** in
  the device's `BOOT_COMPLETED` receiver list — verified after the upgrade, where
  before the upgrade OpenPanel appeared in none of the device's 118 boot
  receivers.
- ADB reachable over USB and over Wi-Fi (the wireless port is not fixed across
  reboots; find it with `adb mdns services` or re-enable in Developer Options).
  On 2026-08-17 it was `192.168.1.193:38265`.
- **Game library mirrored from ArborXR (2026-08-17).** The Fire is not an ArborXR
  target, so the Pink TCL app list was replicated by hand: each game's exact
  ArborXR build (the one the TCL's release channel points at) was pulled from the
  build's signed `downloadUrl` (`abxr-cli apps versions <appId>`), SHA-256-checked
  against ArborXR's checksum, and `adb install -r`'d — then **every one was
  launched on the tablet and watched for 40-90 s** (process alive, foreground
  activity, `FATAL EXCEPTION`, GMS dialogs, screenshot), because Fire OS has no
  Google Play services and MicroG does not help there.

  Runs (14, installed now): Among Us, Bloons TD 6 (wants a 561 MB in-app
  content download on first run), Epic Stickman, Hill Climb Racing, Idle Egg
  Factory, Lily's Garden (~90 s first load, then a "new version" nag with an X),
  Minecraft (Microsoft sign-in prompt has "Maybe later"; plays offline), Mob
  Control (~90 s first load), Shadow of Death (**demanded "Display over other
  apps" and bounced to Settings until `appops set … SYSTEM_ALERT_WINDOW allow`
  was granted over adb — now granted**), Sniper 3D, Subway Surfers, Swamp
  Attack, TDS, The Archers.

  Does not run on Fire OS — **uninstalled again**: Dan The Man and Into the Dead
  (modal "won't run without Google Play services, which are not supported by
  your device"), Plants vs Zombies (native abort inside
  `play-services-measurement` during start), Machinarium (opens
  `com.pairip.licensecheck.LicenseActivity`, the Play licensing check, then
  exits), Fallout Shelter (the ArborXR package is a LiteAPKs *installer stub*,
  not the game). Not attempted: Human Fall Flat (1.26 GB, no room), MicroG.

  `/data` is 10 GB with ~2.2 GB free after this. Standalone mode is a deny-list
  (`hiddenAppIds`, empty on this tablet), so the games appeared in Apps & Games
  with no Admin step. **Rule for future additions:** a static GMS reference in
  the manifest means nothing (the four games that always worked have them too);
  the only test is launching it on the tablet and reading the screen and logcat.

### The debug key and the uninstall block

**The Fire tablet cannot be updated from a machine that lacks the original debug
keystore.** Both escape routes are closed:

```console
$ adb install -r app-debug.apk
Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: ... signatures do not match ...]

$ adb uninstall com.orgista.openpanel.debug
Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]

$ adb shell pm uninstall --user 0 com.orgista.openpanel.debug
Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]
```

`com.amazon.parentalcontrols` is **Profile Owner on user 0** and blocks the
uninstall. Only that profile owner can clear `setUninstallBlocked`; there is no
ADB command for it. OpenPanel itself is *not* a device admin on this tablet.

Therefore: **build Fire APKs on the Mac Studio**, whose
`~/.android/debug.keystore` matches the installed certificate
(`11b408c4…36f8`). A build from any other machine can neither upgrade nor replace
the install. If that keystore is ever lost, the only remaining paths are clearing
the uninstall block through the Amazon Kids UI on the device, or installing the
production package `com.orgista.openpanel` alongside as a separate app.

### Work completed and shipped to the device

Compiled, unit-tested, and **installed on the tablet** (1.1.47-debug / code 56,
then 1.1.48-debug / code 57). The boot receiver's registration is confirmed
on-device; the end-to-end reboot behaviour is the one thing still to observe (see
Remaining steps):

- `KioskBootReceiver` + `RECEIVE_BOOT_COMPLETED` — the kiosk had no way to re-arm
  after a reboot.
- Foreground-window audit in `HomeGestureAccessibilityService.onServiceConnected()`
  at 750 ms / 2.5 s / 6 s — the redirect previously only fired from window-change
  events, which never arrive for a launcher that settled before the service bound.
- `FireLauncherRedirect` broadened from one hardcoded package to an exact-name set
  plus a **Fire-only** resolved-HOME fallback.

- `ArtImage` (1.1.48): inline `data:`/`blob:` art is never cache-busted on retry,
  pending retry timers are cancelled when the src changes, and inline art goes
  straight to the fallback (and `onPermanentFailure`) instead of retrying bytes
  that cannot change. Verified on the tablet: all six channel tiles render art;
  previously all six showed letter avatars.

Validation: 77/77 Android unit tests, 97/97 UI tests (5 new for `ArtImage`), 0
lint errors. A built APK verified as `versionCode 57 / 1.1.48-debug`, signed with
the `11b408c4…` certificate, with both the permission and the receiver present in
the merged manifest.

### Remaining steps

Steps 1-3 (rebuild with the matching key, in-place upgrade, grant verification)
were completed on 2026-08-17. What is left:

1. **Arm kiosk, reboot, and confirm OpenPanel returns with no touch input.** This
   is the test that converts the boot fix from "registered" to "works". Set
   `kioskEnabled` through the Admin UI rather than by editing the prefs file, so
   the app's own state machine runs.
2. **Confirm Exit Kiosk still leaves the Amazon launcher in place across a
   reboot** — `KioskBootReceiver` is gated on the HOME alias precisely so that
   exiting kiosk survives a restart. A regression here would strand the tablet in
   kiosk.
3. Watch `adb logcat -s OpenPanel` across the reboot. The receiver logs
   `Boot completed; returned to the standalone OpenPanel kiosk` on success, and
   `Boot activity start was refused; accessibility redirect will retry` if
   Android's background-activity-start restriction blocks it — in which case the
   `onServiceConnected()` foreground-window audit is the fallback that should
   still recover it.
4. Re-check the live HOME package after any Fire OS update:

   ```sh
   adb shell cmd package resolve-activity \
     -a android.intent.action.MAIN -c android.intent.category.HOME
   ```

### Backup taken before any device change

App data was archived before the (failed) reinstall attempt — 4.7 MB, 95 entries
covering `shared_prefs`, `app_webview` (the Local Storage leveldb holding the
admin PIN and catalog), and `files`. Recreate with:

```sh
adb exec-out "run-as com.orgista.openpanel.debug \
  tar -cf - shared_prefs app_webview files" > fire-appdata-backup.tar
```

Restore with `run-as ... tar -xf -` after a reinstall. Android's `adb backup` is
unavailable here because the manifest sets `allowBackup="false"`.

---

## Field research session — 2026-08-18 (ONN 11 Pro)

Reproduction and root-cause notes for an 11-item defect/feature list. **No fixes were
applied.** Every claim below is either reproduced on hardware or traced to a specific
line of source; where something could not be reproduced it says so explicitly.

### Test rigs

Two tablets are in play and they are **not** interchangeable — a finding in itself,
since an issue reproduced on one may not exist on the other.

| | ONN 11 Pro (primary) | Fire 7 |
| --- | --- | --- |
| Model / vendor | `100146660` / BOE `onn11TabletPro` | `KFQUWI` / Amazon `quartz` |
| Serial | `ONN11PRO00156016` (USB) | `GR71WE05531501MX` |
| Android | **14 (API 34)** | 11 (API 30) |
| Display | 1840×1280 landscape @280dpi | 1024×600 @160dpi |
| Package | `com.orgista.openpanel` (**production**) | `com.orgista.openpanel.debug` |
| Version during session | **1.1.48 (code 57)** | 1.1.52 (code 61) |
| Device Owner | **Yes** — "Managed-device controls available" | **No** — Amazon Parental Controls is Profile Owner |
| Admin PIN | 4523 | different |

The ONN cannot be updated from a machine lacking the **production** signing key
(Keychain, Mac Studio). The Fire cannot be updated from a machine lacking the original
**debug** keystore, and cannot be uninstalled at all (Profile Owner blocks it).

### Driving the UI over ADB — two traps

1. **`uiautomator dump` reports stale content for WebView modals.** Opening Settings
   appeared to fail repeatedly; screenshots showed it had opened every time. Verify UI
   state with `exec-out screencap`, and use `uiautomator` only for element bounds.
2. **The screensaver eats the first tap** ("TAP ANYWHERE TO WAKE"). Budget a throwaway
   tap before any interaction.

---

### Item 1 — Admin PIN

`4523` is the **ONN's** PIN. It is rejected on the Fire, which retains an older PIN.
Not a defect; recorded so the next session does not repeat the misdiagnosis.

### Item 2 — "Forget network does not work" — CONFIRMED, platform-limited

Root cause is in `SystemBridgePlugin.forgetWifi()`:

- On **API 29+** it routes to `forgetSuggestedWifi()`, which calls
  `wifi.removeNetworkSuggestions(new ArrayList<>())`. That only removes suggestions
  **OpenPanel itself created**.
- It **ignores the `ssid` argument entirely** on that path.
- It **always calls `call.resolve()`**, so the UI reports success, clears the error
  state, and re-scans — leaving the network visibly present.

Verified non-destructively on both tablets (the connected SSID was not forgotten,
since its password was not available to rejoin):

```console
$ adb shell dumpsys wifi | grep -i suggestion
num_saved_networks_with_configured_suggestion: 0
num_multiple_suggestions: 0

$ adb shell cmd wifi list-networks
Network Id  SSID        Security type
0           Starbucks   wpa2-psk
```

OpenPanel owns **zero** suggestions, and the saved network was created by the system.
So the call is a guaranteed no-op that reports success.

**This is an Android platform boundary, not a simple bug.** Since Android 10 a normal
app cannot remove a network it did not create; `getConfiguredNetworks()` is filtered and
`removeNetwork()` is restricted. Real options: (a) act as Device Owner via
`DevicePolicyManager` (available on the ONN, **not** on Fire), (b) deep-link to the
system Wi-Fi panel and let the user forget it there, or (c) keep the button but surface
an honest "managed by Android" state. What must change regardless: **stop resolving
successfully when nothing was removed.**

### Items 3 & 4 — Wi-Fi admin gating — item 4 already correct, item 3 inverted

Reproduced on the ONN. As a guest, in-range networks render **"🔒 Admin only"** and
cannot be joined. After unlocking with the PIN, each row gains **Connect**, and the
connected network gains a red **Forget**.

- **Item 4 (forget = admin-only): already the current behaviour.** No change needed.
- **Item 3 (join without admin): the opposite of current behaviour on 1.1.48.**

Note the source has since moved. `connectivityPolicy()` in `SettingsModal.tsx` on the
1.1.52 tree already reads `canJoinWifi: true` with a comment describing exactly the
policy item 3 asks for, so this may already be fixed between 1.1.48 and 1.1.52 —
**re-verify on a current build before implementing.** What is definitely still missing is
the **admin-configurable lock**: `canJoinWifi` is hardcoded, not persisted policy, so an
admin cannot choose to restrict joining.

Fire OS is a separate case: it has no in-app network list at all. Its panel reads
"Saved networks stay in Fire OS" with a single *Add a network* button that opens the
Fire OS picker, so items 3/4 are effectively moot there.

### Item 5 — Device Health should uninstall, not just hide — PARTLY EXISTS

Uninstall is already implemented: `SystemBridgePlugin` fires
`Intent.ACTION_UNINSTALL_PACKAGE` with `EXTRA_RETURN_RESULT`, and `DeviceHealthPanel`
renders an "Uninstall…" button. The gap is **which apps get it**:

```java
app.put("canUninstall", !isSystemApplication(info));   // user apps only
result.put("canManageApps", deviceOwner);              // hide/restore needs Device Owner
```

- **System apps can never be uninstalled**, only hidden — correct for user 0, but it
  means preinstalled bloat has no removal path.
- **Hide requires Device Owner.** True on the ONN; **false on Fire**, where Device Health
  is effectively read-only.

The requested "disable if uninstall is unavailable" tier does not exist. Note that the
useful middle ground — `pm disable-user --user 0` — is an ADB/shell capability an app
cannot invoke for itself, which is precisely why the Fire runbook uses ADB. Realistic
ladder: uninstall (user apps) → `setApplicationHidden` (Device Owner) → report-only.

### Items 6 & 7 — Admin tab consolidation — CONFIRMED, justified

Eight tabs render today: **Apps · Health · Books & Audio · YouTube · Display · Kiosk
Lock · Logs · Security** (`ADMIN_TAB_DETAILS` in `AdminPanel.tsx`). Even at 1840px wide,
**"Books & Audio" and "Kiosk Lock" already wrap to two lines**, so the bar is cramped on
the *large* tablet — considerably worse on the Fire's 1024px.

Requested shape: keep **Apps** and **Health**; merge **Books & Audio, YouTube, Kiosk
Lock, Security, Display** into one tab with section headings.

**Open decision: Logs.** It was not named in the merge list. Recommend it stays separate —
it is a diagnostic surface, not content or policy, and burying it inside a long scrolling
settings tab makes support harder. Flagging rather than assuming.

**Item 6 also asks for new Books & Audio copy.** Current strings lean on the internal
feature name. Suggested direction, matching the existing plain-spoken voice:

> **Books & Audio** — "Add EPUB, PDF and audiobook files, or connect a library catalog.
> Titles download for offline reading and listening, and nothing appears here until you
> add it."

That also satisfies the "hidden unless enabled" behaviour already in the product.

### Item 8 — YouTube recommendations not tappable / no resolution control — CONFIRMED

Reproduced on the ONN by playing a channel video. **Tapping the player surface reveals
only OpenPanel's own overlay — "✕ Exit" and "⛶ Full Screen".** YouTube's native control
bar never appears, at any tap depth. Consequences:

- **No scrubber**, so a video cannot be seeked.
- **No settings gear**, which is the only supported place a viewer picks quality.
- **No reachable end-screen**, so YouTube's own recommendations cannot be clicked.

Three contributing causes in `YouTubePlayer.tsx` / `youtube.ts`:

1. A **full-surface overlay button** (`absolute inset-0 z-10`) is rendered whenever
   controls are hidden, and takes the tap before the iframe sees it.
2. The iframe `sandbox` is `allow-scripts allow-same-origin allow-presentation` — with
   **no `allow-top-navigation`**, so even a delivered click on a recommendation cannot
   navigate.
3. `rel=0` does **not** remove related videos (behaviour changed in Sept 2018); it
   restricts them to the same channel.

**On the resolution request specifically:** `embedUrl()` sets no `vq` parameter, and
YouTube deliberately ignores `vq` on embeds while the IFrame API's `setPlaybackQuality()`
has been advisory-only since ~2019 — the player picks quality from bandwidth and
viewport. **An admin "set resolution" control cannot be enforced through this embed.**
Honest options: expose a data-saver hint and label it as a preference, or move to a
native player — which for YouTube content carries ToS problems. Recommend deciding the
product answer before any implementation.

### Item 9 — Crash when picking a same-channel recommendation — MECHANISM IDENTIFIED

**Not reproduced live** (the end-of-video picker requires the video to finish, ~20 min,
and no scrubber exists — see item 8). The mechanism is unambiguous in source:

```js
const playRelatedVideo = useCallback((video) => {
  const player = playerRef.current;
  if (player?.loadVideoById) player.loadVideoById(video.videoId);
  else setVideoOverride(video.videoId);        // fallback remounts the iframe
}, []);
```

with the player created in an effect keyed:

```js
}, [allowSameChannelRecommendations, closePlayer, item.sourceId]);
```

`videoOverride` is **absent from that dependency array**, while the iframe is
`key={videoOverride ?? item.id}`. On the fallback path React therefore **unmounts and
remounts the iframe**, the effect does **not** re-run, and `playerRef.current` is left
bound to a **destroyed DOM node**. The iframe also carries a fixed
`id="openpanel-youtube-player"`, so the replacement element reuses the id while the stale
`Player` still points at the old one. Any subsequent call — `loadVideoById`, the
unmount-time `destroy()`, state polling — operates on a dead player, which matches the
reported "crashes and doesn't allow to resume normal gui".

The JS-API path has a latent variant of the same problem: after `loadVideoById` the
iframe `src` still encodes the *original* video id, so state derived from `item` and the
actual playing video diverge.

### Item 10 — Verified channel shows no icon — ROOT CAUSE FOUND

The search preview in `YouTubeSearchPanel.tsx` renders the remote URL directly:

```jsx
{result.thumbnailUrl ? (
  <img src={result.thumbnailUrl} ... />
```

The saved launcher tile does **not** — it goes through **`fetchImageAsDataUrl`**, the
native HTTP path with IPv4-first DNS added specifically "for durable channel art"
(commit `4cb3c07`, plus `Ipv4FirstDns.java`).

So the two surfaces fetch channel art by different routes, and only one of them uses the
hardened path. Observed on the ONN: the saved **Marques Brownlee** tile renders its
avatar correctly, and the channel browser header shows it too — consistent with the
preview being the only place that loads the image straight from `googleusercontent.com`
inside the WebView. **Fix direction: route the preview through `fetchImageAsDataUrl` as
well.** The thumbnail itself resolves fine — `YouTubeChannelResolver` scrapes `og:image`
and returns it — so this is delivery, not lookup.

### Item 11 — Absorbing personalDNSfilter into OpenPanel — BLOCKED ON LICENSING

**personalDNSfilter is [GPL-2.0](https://github.com/IngoZenz/personaldnsfilter).
OpenPanel is MIT.** GPL-2.0 is strong copyleft: forking that code into the OpenPanel APK
would make the combined, distributed work GPL-2.0 and force OpenPanel to relicense. That
is a licensing decision, not an engineering one, and it should be settled before any
design work.

Two further constraints, independent of licence:

- **Android allows one active VPN per user.** DNS filtering via `VpnService` is
  mutually exclusive with any other VPN, so absorbing it removes a capability rather than
  adding one.
- **User consent is mandatory.** No app can create a VPN silently; Android always shows
  its own consent dialog. "Auto-enable filtering" is not achievable.

The **current architecture already looks correct**: OpenPanel supervises
`dnsfilter.android` as a separate app, reports its state, and can set it as always-on
with `lockdown=false` when Device Owner. Verified healthy on the Fire
(`always_on_vpn_app=dnsfilter.android`, `lockdown=0`, Private DNS off).

For the "auto-detect telemetry the user can remove" half — that is a **DebloatCatalog**
feature, not a DNS one, and needs no forking. The existing reviewed exact-name catalog
plus the Device Health surface is the right home. Recommended path: keep supervising the
GPL app at arm's length, and extend `DebloatCatalog` with a reviewed telemetry profile.
If in-process filtering is truly wanted, a clean-room MIT resolver or an
Apache/MIT-licensed library is the only route that preserves OpenPanel's licence.

---

### Additional defect found while investigating

**The top-edge shade guard is visible over OpenPanel itself.** On the ONN the
accessibility overlay is a live window at `frame=[0,0][1840,48]` with
`mViewVisibility=0x0` (VISIBLE) while OpenPanel is the foreground app:

```console
$ adb shell dumpsys window windows | grep -A3 "OpenPanel top edge guard"
mAttrs={(0,0)(fillx48) gr=TOP CENTER ... ty=2032 fmt=TRANSLUCENT
mViewVisibility=0x0 mHaveFrame=true mObscured=false
Frames: ... frame=[0,0][1840,48]
```

`syncShadeGuardVisibility()` is supposed to hide it when the foreground package is
OpenPanel, but it only recomputes on accessibility events, so it can be left visible
after a transition where the last observed window was something else. Its `onTouchEvent`
returns `true`, so it consumes touches in a 48px band across the top — directly over the
status-bar row that hosts the settings, Wi-Fi and Bluetooth buttons. It was *not* the
cause of the tap failures in this session, but it should not be visible over OpenPanel.

### Summary

| # | Item | Status |
| --- | --- | --- |
| 1 | PIN 4523 | ONN only; Fire differs |
| 2 | Forget network | **Confirmed** — silent no-op; platform-limited |
| 3 | Wi-Fi join without admin | **Confirmed inverted** on 1.1.48; may be fixed in 1.1.52; admin lock still missing |
| 4 | Forget = admin-only | **Already correct** |
| 5 | Health uninstall | Partly exists; no disable tier; hide needs Device Owner |
| 6 | Books & Audio copy + merge | Confirmed; copy proposed |
| 7 | Tab consolidation | Confirmed; **Logs placement undecided** |
| 8 | Recs untappable / resolution | **Confirmed**; resolution **not achievable** via embed |
| 9 | Same-channel rec crash | Not reproduced live; **mechanism identified** |
| 10 | Missing channel icon | **Root-caused** — preview bypasses `fetchImageAsDataUrl` |
| 11 | personalDNSfilter fork | **Blocked** — GPL-2.0 vs MIT |
| — | Shade guard over own header | **New defect found** |

---

## Follow-up research — 2026-08-18 (licensing, YouTube capability)

### Relicensing personalDNSfilter — not available to us

The proposal was "OSS to OSS should be OK". That is not the test. What matters is
**compatibility direction**:

- **MIT → GPL is allowed.** MIT code can be absorbed into a GPL project; the combined
  work ships as GPL.
- **GPL → MIT is not.** [GPL-2.0](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html)
  §2 requires derivative works to be licensed *as a whole* under the GPL. Stripping
  copyleft is exactly what it forbids.

**Only the copyright holders can relicense**, and this project does not have one. GitHub
reports **15 distinct contributors**:

| Contributor | Commits |
| --- | ---: |
| IngoZenz | 838 |
| Ridje | 30 |
| acsway878787, smed79 | 4 each |
| TheEvilSkeleton | 2 |
| 10 others | 1 each |

A relicence therefore needs written agreement from **all 15**, or the removal and
clean-room rewriting of every non-consenting contribution. The long tail is small
(1–4 commits each) so it is not theoretically impossible, but it depends on tracing and
getting agreement from ten drive-by contributors, some from years ago.

**Ranked options:**

1. **Keep supervising it as a separate app (current architecture).** GPL's "mere
   aggregation" clause covers shipping two independent apps on one device. Zero legal
   risk, already implemented, already verified working on the Fire. **Recommended.**
2. **Ask Ingo Zenz for a dual licence** (`GPL-2.0 OR MIT`). Costs one email. He still
   needs the other 14 to agree, so treat a yes as the start of the process, not the end.
3. **Clean-room MIT resolver**, or an Apache/MIT-licensed DNS library. Preserves
   OpenPanel's licence at the cost of building and maintaining a resolver.
4. **Relicense OpenPanel to GPL.** Legal, and almost certainly unacceptable for this
   product.

Note this changes nothing about the two hard Android constraints already documented:
one active VPN per user, and mandatory user consent for `VpnService`. Absorbing the
filter would not remove either.

### YouTube — what is fixable, and what is not

**Fixable, straightforwardly:**

- **Item 9 (crash on same-channel pick).** Add `videoOverride` to the player effect's
  dependency array, or stop remounting via `key` and drive playback solely through
  `loadVideoById`. Drop the fixed iframe `id` in favour of a ref.
- **Item 10 (missing channel icon).** Route the search preview through
  `fetchImageAsDataUrl`, as the launcher tile already does.
- **Item 8 (recommendations untappable).** Remove or shrink the full-surface overlay
  button, and add `allow-top-navigation-by-user-activation` to the iframe sandbox.

**Video resolution — not as an admin setting.** Confirmed against
[YouTube's player parameters](https://developers.google.com/youtube/player_parameters):
there is **no `vq` or equivalent quality parameter**, and `setPlaybackQuality()` has been
advisory-only since ~2019. An enforced "always 720p" is not achievable through an embed.

**What ReVanced does, and why it does not transfer.** ReVanced is a *patcher*: it
rewrites the official YouTube APK's bytecode (Kotlin patches over smali) and hooks the
native client's own quality API on each video start. That works because it is modifying
YouTube's real app, which has an internal quality selector. OpenPanel embeds the **web**
player and has no such surface. Adopting the approach would also mean shipping a patched
YouTube APK — a YouTube ToS violation, and an unacceptable risk for a child-facing kiosk.
Worth noting even ReVanced users report the setting
[sticking at a lower quality](https://github.com/ReVanced/ravanced-patches/issues/3872)
once an unavailable resolution is requested.

**The realistic answer for quality: stop hiding YouTube's own controls.** Fixing item 8's
overlay makes YouTube's native control bar reachable, which includes its settings gear —
the one supported place a viewer selects quality. That converts "add an admin resolution
setting" (impossible) into "let the user pick quality" (already built by YouTube).

### Proposed per-source playback policy

The request to treat single videos and channels differently is sound, and maps cleanly
onto supported parameters. Approving a whole channel is a broader grant than approving
one video, so the player can justifiably be more permissive.

| Behaviour | Single video | Channel |
| --- | --- | --- |
| `autoplay=1` | yes | yes |
| `cc_load_policy=1` (captions on) | **yes** | optional |
| Close when finished | **yes** — `ENDED` → `closePlayer()` | no — show same-channel picker |
| YouTube native controls | minimal | **full** (quality, captions, speed) |
| Same-channel recommendations | no | yes |

`cc_load_policy=1` and `cc_lang_pref` are both still supported, so captions-on-by-default
is a one-line change to `embedUrl()`. The close-on-finish path already exists — the
`onStateChange` handler treats `ENDED` as "return to OpenPanel" when same-channel
recommendations are off. What is missing is that these are currently **global**, not
per-source: `embedUrl()` takes only the source, and `allowSameChannelRecommendations` is
a single setting. Making the policy a property of the approved source is the actual work.

### Reference check — how Fully implements YouTube

`Samples/` holds three commercial Fully Kiosk builds (do-not-redistribute; examined at
**mechanism level only**, no code reused):

| APK | Package | Version |
| --- | --- | --- |
| Fully Kiosk Browser | `com.fullykiosk.kiosk` | 1.60.1 |
| Fully Single App Kiosk | `com.fullykiosk.singleapp` | 1.20.1 |
| Fully Video Kiosk | `com.fullykiosk.videokiosk` | 1.20.1 |

**Fully Video Kiosk uses the same mechanism OpenPanel does** — YouTube's IFrame API
inside a WebView. Evidence from its dex:

```text
tag.src = "https://www.youtube.com/iframe_api";
function onYouTubeIframeAPIReady() { ... }
<title>Fully YouTube Player</title>
^(?:https?://|//)?(?:www\.|m\.|.+\.)?(?:youtu\.be/|youtube\.com/(?:embed/|v/|shorts/ ...
```

It builds a local HTML page hosting the iframe, extracts the 11-character video ID and
playlist ID by regex, and exposes a `fullyYtInterface` JS bridge. Its player config:

```js
playerVars: {
  'autoplay': 1,
  'controls': 0,
  'loop': 1,
  'rel': 0,
}
```

**Two conclusions that settle the resolution question.**

1. **They ship ExoPlayer and still do not use it for YouTube.** The APK contains ~30
   `androidx.media3`/ExoPlayer references, with full `SubtitleView` and DRM handling —
   used for local and direct-URL video files. YouTube is deliberately routed through the
   iframe instead. A vendor with a complete native player already integrated chose not to
   point it at YouTube, which is the expected outcome of YouTube's terms on stream
   extraction.
2. **They make no attempt at quality control whatsoever.** Searching the dex for
   `setPlaybackQuality`, `suggestedQuality`, `hd1080`, `hd720`, `videoQuality` returns
   **nothing**. Nor do they set `cc_load_policy` — their subtitle handling is entirely
   media3, i.e. for their own playback, not YouTube.

This independently corroborates the finding above: enforced resolution is not achievable
for embedded YouTube by any legitimate route, and the market leader does not pretend
otherwise.

**Where OpenPanel should diverge:** Fully Video Kiosk is digital-signage software —
`controls: 0` and `loop: 1` suit unattended playback with no viewer interaction.
OpenPanel is interactive and child-facing, so `controls: 1` plus a reachable YouTube
control bar is the correct choice, and is what makes viewer-selected quality possible.
The per-source policy table above remains the right model.

### Corrections and decisions — 2026-08-18 (later)

**Item 3 — the ONN genuinely does not have the fix.** The guest-Wi-Fi change is real and
is in current source, but it missed the build on the device by 16 seconds:

```text
a726571  2026-08-17 20:36:55  release: bump to 1.1.48 (versionCode 57)   ← on the ONN
7233289  2026-08-17 20:37:11  settings: guests may join Wi-Fi and pair
                              Bluetooth; forget/unpair/off stay admin
```

`7233289` is confirmed an ancestor of UI HEAD (`e008ad1`), so it ships from 1.1.49
onward. **Fixed in code, never verified on hardware.** The remaining work is unchanged:
`canJoinWifi` is still hardcoded `true`, so the admin-configurable lock does not exist.

**Item 11 — earlier conclusion was too narrow.** The blocker is *GPL copyleft
specifically*, not open source. Permissively-licensed OSS can be absorbed into an MIT
product without relicensing:

| Project | Licence | Usable inside OpenPanel? |
| --- | --- | --- |
| [personalDNSfilter](https://github.com/IngoZenz/personaldnsfilter) | **GPL-2.0** | ❌ forces the whole APK to GPL |
| [RethinkDNS](https://github.com/celzero/rethink-app) | **Apache-2.0** | ✅ compatible |
| [dns-shield](https://github.com/XiangWang2000/dns-shield) | **Apache-2.0** | ✅ compatible |
| OkHttp `dnsoverhttps` | **Apache-2.0** | ✅ already a dependency |

Building an equivalent is also unambiguously legal on its own terms — copyright protects
the *expression*, not the idea of DNS filtering. Nothing stops OpenPanel implementing
`VpnService` + a DoH resolver + blocklists directly.

RethinkDNS is the closest permissive analogue (DoH/DoT/DNSCrypt, per-app firewall,
`VpnService`, no root; Kotlin UI over a Go network stack forked from
Jigsaw's `outline-go-tun2socks`). Note Apache-2.0 still carries obligations — retain
`NOTICE`/attribution and the patent grant — so its notices belong in the third-party
section, but it does **not** infect OpenPanel's MIT licence.

Also unchanged by any of this: one active VPN per user, and mandatory user consent for
`VpnService`. Those are Android limits, not licence limits.

**Item 5 — decision: non-system disable, reversible.** System apps stay
uninstall-exempt. For non-system apps the reversible tier is
`DevicePolicyManager.setApplicationHidden`, which preserves app data and is undoable —
available wherever OpenPanel is Device Owner (the ONN), unavailable on Fire. Note a plain
app cannot call `setApplicationEnabledSetting` for *another* package, so Device Owner
hide is the only in-app mechanism; ADB `pm disable-user --user 0` remains the Fire path.

**Item 7 — decision: Logs keeps its own tab, debug builds only.** Gate the tab on the
debug build type rather than merging it into the consolidated settings tab. Caveat for
testing: the ONN runs the **production** package, so Logs will disappear from the main
test device once this lands.

**Item 2 — decision: behaviour is device-class dependent.** Where Device Owner is
available, remove the network through `DevicePolicyManager`. On Fire OS, keep delegating
to the Fire OS Wi-Fi picker. In both cases stop reporting success when nothing was
removed.

---

## QOL scoping + Fully lockdown teardown — 2026-08-18

### 1. Themes and logos (school branding)

No blocker. Branding is currently compile-time: `gradientFor(packageName)` in `App.tsx`
derives tile colours from a hash, `OpenPanelBrand` is a fixed component, and the launcher
icon set lives in `res/mipmap-*`. A deployment-time theme needs (a) a small theme record
(logo image, accent colour, background image/gradient, optional wordmark) stored beside
the existing admin config, and (b) CSS custom properties replacing the current hardcoded
`oklch(...)` literals.

Two things to respect: the master content policy forbids shipping any customer's assets
in the repo, so themes must be added post-deployment through the admin UI; and the logo
must go through `fetchImageAsDataUrl` (see item 10 above) rather than a remote `<img>`.
Note Fully takes a cruder route — it holds `android.permission.SET_WALLPAPER` and themes
the *device*, not the app.

### 2. Device never really sleeps — DIAGNOSED

Two independent causes, neither of which is a bug in OpenPanel's own logic:

```text
screen_off_timeout        = 60000   (60s)
sleep_timeout             = -1      (disabled)
stay_on_while_plugged_in  = 15      ← AC|USB|WIRELESS|DOCK
```

**`stay_on_while_plugged_in = 15` means the screen can never sleep on any charger**, and
these units live on chargers. OpenPanel does not set this — no source reference exists —
so it came from provisioning/ADB and should be corrected there.

Second, what users see instead of sleep is **OpenPanel's own in-app screensaver**: a
WebView overlay ("Tap anywhere to wake", `App.tsx:796`) on the admin Display tab's
`inactivityTimeout`. The backlight stays at full brightness rendering a clock. OpenPanel
holds no wakelocks, so nothing else is keeping it awake.

Fully solves the same problem with dedicated settings worth copying:

| Fully key | Behaviour |
| --- | --- |
| `keepSleepingIfUnplugged` | allow real sleep on battery |
| `screensaverBrightness` | dim the panel during screensaver instead of full brightness |
| `screensaverDaydream` | hand off to Android's real Daydream/dream service |
| `keepOnWhileFullscreen` | only hold the screen on during playback |
| `motionDetection` (camera / acoustic / proximity) | wake on approach |

Recommended: dim during the OpenPanel screensaver, let the device genuinely sleep after a
second longer timeout, and stop provisioning `stay_on_while_plugged_in=15`.

### 3. Profiles — scoping

Two very different costs, and the cheap one probably answers the question.

**In-app profiles (recommended for an alpha).** A profile is just a named bundle of the
config OpenPanel already persists — allowed apps, YouTube sources, library catalogs,
theme, kiosk policy. Switching swaps the active bundle. No OS involvement, works on Fire
and non-Device-Owner devices, and is largely a data-model refactor: today those settings
are single-valued in WebView storage. This is the alpha that tells you whether profiles
are worth it.

**OS-level multi-user.** A Device Owner can create secondary users with genuinely
separate app data. Real isolation, but: unavailable on Fire (no Device Owner), slow user
switching, storage duplication, and OpenPanel would need re-provisioning per user. Only
worth it if profiles must be *security* boundaries rather than *presentation* ones.

Start in-app; escalate only if the alpha shows a need for hard isolation.

### 4. Fully lockdown teardown

`Fully-Video-Kiosk-v1.20.1.apk` installed to the ONN for hands-on comparison
(`com.fullykiosk.videokiosk`). Feature set recovered from its settings keys — **mechanism
study only, no code reused.**

**Hardware and system UI**

`disableHomeButton` · `disablePowerButton` · `disableVolumeButtons` · `disableStatusBar` ·
`disableLockscreenPulldown` · `forceImmersive` · `disableScreenshots` ·
`disableNotifications` · `forceDndInKioskMode` · `disableKeyguard` · `disableMultiWindowApps`

**App containment**

`kioskAppWhitelist` · `kioskAppBlacklist` · `disableOtherApps` · `disableAndroidMarket` ·
`disableAndroidBrowser` · `disableYouTube`

**Radios and peripherals**

`disableWifi` · `disableBluetooth` · `disableHotspot` · `disableCamera` ·
`disableIncomingCalls` / `disableOutgoingCalls`

**Exit protection**

`kioskPin` · `kioskExitGesture` · `kioskBluetoothPin` (unlock by paired device) ·
`kioskWifiPinAction` · `unlockKiosk` · `kioskTestMode`

**Remote administration** — the biggest capability gap

`remoteAdminLan` · `remoteAdminPassword` · `remoteAdminScreenshot` · `remoteAdminCamshot` ·
`remoteAdminFileManagement` · `remoteAdminAdvertising`

A built-in LAN admin server: remote screenshots, camera shots, file management, device
control. OpenPanel has no equivalent — ArborXR covers some of it for managed fleets, but
nothing for standalone/Fire deployments.

**Presence detection**

`motionDetection` with camera, acoustic and proximity backends, plus sensitivity/FPS
tuning — used to wake from screensaver when someone approaches.

**Architectural contrast worth noting.** Fully requests `REORDER_TASKS`,
`EXPAND_STATUS_BAR`, `DISABLE_KEYGUARD`, `KILL_BACKGROUND_PROCESSES` and `WAKE_LOCK`, and
notably **does not** request `SYSTEM_ALERT_WINDOW`, `WRITE_SETTINGS` or
`PACKAGE_USAGE_STATS`. It achieves foreground-return with `REORDER_TASKS` and status-bar
control with `EXPAND_STATUS_BAR`, where OpenPanel uses an accessibility service plus
overlay windows. Fully's route needs fewer scary "special access" grants; OpenPanel's
survives on devices where those permissions are unavailable. Neither is strictly better —
but it explains why Fully's setup asks for less.

### 5. YouTube player — already scoped

See the earlier sections. Fixes identified: dependency-array/remount for the crash,
overlay + sandbox for tappability, `fetchImageAsDataUrl` for the icon. Resolution cannot
be admin-forced; the fix is exposing YouTube's own controls so the viewer chooses.

### 6. Hard edge under the top bar — DIAGNOSED

```jsx
className="fixed top-0 left-0 right-0 h-16 ..."
style={{ background: "linear-gradient(to bottom, oklch(11% 0 0) 60%, transparent)" }}
```

The bar is a fixed 64px with the gradient **fully opaque until 60%**, leaving only ~25px
to reach transparent. That abrupt stop is the visible seam, worst over bright hero art.
Fix direction: begin the fade at 0–20%, use several intermediate stops (oklch gradients
band badly with only two), scale the height with viewport rather than pinning 64px, and
consider `backdrop-filter: blur()` for separation that does not depend on opacity alone.

### 7. Blurry / clipped app icons — TWO ROOT CAUSES

In `SystemBridgePlugin.drawableToBase64()`:

```java
int size = 144;                              // fixed for every device
drawable.setBounds(0, 0, size, size);        // adaptive icons drawn unmasked
drawable.draw(canvas);
```

**Blur.** Every launcher icon is rasterised once at **144×144** regardless of density or
display size, then upscaled by CSS. The ONN is 1840×1280 at 280dpi and renders hero art
around 290 CSS px, so it is magnifying a 144px source — exactly the reported "blurrier the
bigger the screen".

**The clipped edge.** `getApplicationIcon()` returns an `AdaptiveIconDrawable` on Android
8+, whose layers intentionally extend past the visible mask (only the central 72 of 108
units is the safe zone). Drawing it flat into a square with no mask renders the full bleed
including background-layer edges, producing a hard square boundary instead of the intended
masked silhouette.

Fix direction: raster at a density-aware size (or emit 2–3 sizes and let CSS choose), cap
by `ActivityManager.getLauncherLargeIconSize()` rather than a literal, and branch on
`AdaptiveIconDrawable` to composite background+foreground through the platform mask.
Icons are already cached per `package@versionCode`, so a larger raster costs memory once,
not per frame.

### 8. Show the OpenPanel version in Settings (About)

**Problem.** OpenPanel never displays its own version anywhere in the UI. There is no way
to tell, from a device in hand, which build it is running.

This is not cosmetic. During the 2026-08-18 session it caused real diagnostic cost: the
ONN was on 1.1.48 while the repo was on 1.1.52, and a Wi-Fi fix that *appeared* absent
turned out to be present in source but cut from the build by 16 seconds. Every "is this
already fixed?" question currently requires:

```sh
adb -s <serial> shell dumpsys package com.orgista.openpanel | grep versionName
```

which needs a workstation, ADB access, and the right serial — none of which a teacher,
parent, or support caller has.

**The plumbing already exists.** `buildConfig true` is enabled in `android/app/build.gradle`
precisely so "native code can read VERSION_NAME/APPLICATION_ID", and `BuildConfig.VERSION_NAME`
is already consumed in `LibraryBridgePlugin.java` for the OPDS user agent:

```java
"OpenPanel/" + BuildConfig.VERSION_NAME + " (OPDS reader)"
```

Note the existing `versionName` field on the bridge is **not** OpenPanel's — it reports
`NetworkPrivacyState.DNS_FILTER_PACKAGE`, i.e. personalDNSfilter's version, and is
unrelated.

**Scope.** Add a bridge getter returning `BuildConfig.VERSION_NAME`, `BuildConfig.VERSION_CODE`
and `BuildConfig.APPLICATION_ID`, then render an **About** block. Suggested contents:

- `OpenPanel 1.1.52 (build 61)`
- package id — makes the debug/production distinction visible, which mattered on the ONN
- device model and Android version — already available via the Device Health call
- optionally the management mode and Device Owner state, which are the first things asked
  in any support conversation

**Placement.** Settings rather than the Admin Panel, so it is reachable without the PIN —
support often needs the version from someone who does not have admin. It also pairs with
the fleet version check added in `70c660f`: that answers "what is deployed across the
fleet", while About answers "what is this device running right now".

---

## Decision: fork personalDNSfilter, or rebuild? — 2026-08-18

**Recommendation: rebuild on permissive components — and only if integrated telemetry
detection is genuinely wanted. Otherwise keep supervising the separate app.**

### Why forking is the wrong trade

Forking is *legal*. The cost is that GPL-2.0 applies to the whole distributed work, and
that reaches further than the DNS feature:

1. **The entire OpenPanel APK becomes GPL-2.0** — not just the filtering code.
2. **The UI repo goes with it.** `cyberbanksy/openpanel-ui` is MIT today and is compiled
   into the same APK, so it becomes part of the same combined work.
3. **Source obligation on every deployment.** Each school, customer, or ArborXR-managed
   fleet receiving the APK may demand the complete corresponding source — and is free to
   redistribute it.
4. **Any future commercial or proprietary licensing option closes.** Dual-licensing needs
   copyright ownership, and 15 contributors hold it.
5. **No patent grant.** GPL-2.0 has no explicit patent clause; Apache-2.0 does.
6. **You inherit a Java DNS stack** to maintain, in exchange for a feature set that is not
   the one being asked for.

Point 6 is the decisive one. **The feature actually wanted — auto-detecting telemetry so a
user can remove it — does not exist in personalDNSfilter.** Forking delivers a generic DNS
filter that still needs the real feature built on top, and charges the entire product's
licence for the privilege.

### Why rebuilding is cheaper than it sounds

DNS-only filtering does not require full packet routing. There is no need for tun2socks or
a Go network stack — the VPN interface intercepts **IPv4 UDP/53** and everything else is
ordinary request handling.

[dns-shield](https://github.com/XiangWang2000/dns-shield) (Apache-2.0, Kotlin, ~35
commits) implements precisely this scope: UDP/53 interception, DoH resolvers, response
caching with query deduplication, per-app bypass and blocklist compilation. It is a
working existence proof that the surface is small, and it is permissively licensed, so it
can be read *and* borrowed from.

The pieces OpenPanel already has:

| Need | Already available |
| --- | --- |
| DoH client | **OkHttp** (Apache-2.0) — already a dependency; `okhttp-dnsoverhttps` module |
| Native HTTP with IPv4-first DNS | `Ipv4FirstDns.java`, `fetchImageAsDataUrl` |
| Package/telemetry policy surface | `DebloatCatalog` + Device Health |
| Device Owner always-on VPN control | already implemented for the supervised app |

So the genuinely new work is a `VpnService` that answers UDP/53 from a blocklist plus a
DoH upstream. That is a contained component, not a subsystem.

Reference options, all MIT-compatible: [RethinkDNS](https://github.com/celzero/rethink-app)
(Apache-2.0, fuller featured — DoH/DoT/DNSCrypt, per-app firewall, but carries a Go stack
forked from Jigsaw's `outline-go-tun2socks`), and [dns-shield](https://github.com/XiangWang2000/dns-shield)
(Apache-2.0, closer to the needed scope). Apache-2.0 obligations are retained `NOTICE`
and attribution — they do **not** affect OpenPanel's MIT licence.

### The honest counter-argument

Building it in-house is not free, and the status quo already works. Before committing:

- **The one-VPN-per-user limit does not go away.** Whether OpenPanel owns the VPN or
  supervises another app, only one can be active.
- **Consent cannot be automated** either way — Android always shows its own dialog.
- **Blocklists need maintenance** — sourcing, updating, and the false-positive risk the TV
  DNS section already warns about, where over-blocking shared Google/CDN domains breaks
  streaming, auth, and updates. That risk transfers to whoever owns the list.

**Decision rule.** If the goal is *DNS filtering*, the current supervised architecture
already delivers it at zero cost and zero risk — keep it. If the goal is *integrated
telemetry detection with one-app deployment and no GPL dependency*, rebuild on
OkHttp + a small `VpnService`, using dns-shield as the reference implementation.

Either way, **do not fork personalDNSfilter.** It is the only option that changes
OpenPanel's licence, and it is the option that delivers least of what was asked for.

---

## Design plan: built-in DNS filter + query log

Status: **design only, nothing implemented.** Supersedes the "fork personalDNSfilter"
option, which is rejected above on licensing grounds.

### Goals

1. DNS filtering inside OpenPanel, with no GPL dependency and no second app to deploy.
2. A **query log** in the AdGuard mould — "`ads.example.com` blocked 1s ago, requested by
   *Subway Surfers*" — so an operator can see what is actually being requested.
3. **Unblock in one action** from that log. This is the point of the feature: over-blocking
   is the main failure mode of DNS filtering, and today it is invisible and undiagnosable.
4. Telemetry discovery: surface the domains a device reaches out to, so a reviewed
   telemetry profile can be built from evidence instead of guesswork.

### Non-goals

- Full traffic inspection or packet routing. **DNS only** — UDP/53. No tun2socks, no Go
  stack, no per-flow proxying.
- Replacing `DebloatCatalog`. Package-level policy stays where it is; this adds the
  network-level view that informs it.
- Blocking apps that bypass DNS (hardcoded IPs, an app's own DoH). That limitation is
  already documented in the TV DNS section and does not change.

### Architecture

```text
VpnService (DNS-only)
  └─ capture UDP/53
       ├─ parse query (QNAME, QTYPE)
       ├─ cache lookup ──────────────► hit: respond
       ├─ blocklist match ───────────► blocked: synth NXDOMAIN / 0.0.0.0
       └─ upstream via OkHttp DoH ───► allowed: forward, cache, respond
                    │
                    └─► QueryLog ring buffer (async, never in the resolve path)
```

Key point: the VPN interface is configured with `addDnsServer(<local>)` and a route
covering only that address, so **only DNS leaves through the tunnel**. Everything else
takes its normal path. This is what keeps the component small and the latency risk
contained.

Reusable today: **OkHttp** (already a dependency) for the DoH upstream,
`Ipv4FirstDns.java` for resolution ordering, and the existing Device Owner always-on VPN
plumbing built for the supervised app.

### Query log — the part that carries the value

Each entry records:

| Field | Source | Notes |
| --- | --- | --- |
| timestamp | monotonic + wall clock | "1s ago" needs both |
| QNAME / QTYPE | parsed query | |
| decision | allowed / blocked / cached / upstream-failed | four states, not two |
| matched rule | blocklist id + line | required for "why was this blocked" |
| requesting app | UID → package | see below |
| upstream latency | DoH round trip | surfaces resolver problems |

**Per-app attribution is the hard part.** Android exposes
`ConnectivityManager.getConnectionOwnerUid(protocol, local, remote)` from **API 29+**,
then `PackageManager.getNameForUid(uid)`. Both test devices qualify (Fire = API 30,
ONN = API 34). Expect gaps: shared UIDs, system resolver traffic, and queries made by
Android itself will not always attribute cleanly. Show "System / unattributed" honestly
rather than guessing — a wrong attribution here is worse than none, because it will be
used to make blocking decisions.

**The log must never sit in the resolution path.** Write to a bounded in-memory ring
buffer (~1–2k entries) and let the UI poll it. DNS is latency-critical; a blocked write or
a disk flush per query would be felt immediately across the whole device.

### UI surfaces

- **Device Health → DNS** already exists (currently TV-only). This becomes its home, and
  should be un-scoped from TV.
- **Live log view**: newest first, filter by decision and by app, search by domain.
- **One-tap allowlist** from a blocked row — the loop that makes the feature worth
  building. Allowlist entries persist as OpenPanel policy, not blocklist edits.
- **Telemetry discovery view**: aggregate by domain over a window, ranked by frequency,
  with an "add to reviewed telemetry profile" action feeding `DebloatCatalog`.

Admin-gated. Query logs reveal browsing behaviour, so guest access is not appropriate even
on a child device.

### Safety and failure modes

- **Fail open.** If the filter crashes or the upstream is unreachable, DNS must fall
  through rather than black-holing the device. The existing `lockdown=false` stance exists
  for exactly this reason and should carry over.
- **Over-blocking is the expected failure.** The TV DNS section already documents shared
  Google/CDN infrastructure breaking streaming, auth and updates when broad domains are
  blocked — and this session lost `dl.google.com` to precisely that class of rule. The
  query log is the mitigation: make the failure visible and one tap to undo.
- **One VPN per user still applies.** Owning the VPN excludes any other VPN, exactly as
  supervising personalDNSfilter does today. No regression, but no improvement either.
- **Consent cannot be automated.** Android always shows its own VPN dialog.

### Privacy and retention

DNS logs are sensitive even on a kiosk. Defaults should be: in-memory only, bounded ring
buffer, cleared on reboot, no export without explicit admin action. Any persistence is
opt-in with a stated retention window. This also keeps the feature clear of the master
content policy, since nothing observed is ever written into the repo or a build.

### Phasing

1. **Alpha — observe only.** VpnService + DoH upstream + query log, **no blocking at all**.
   Delivers telemetry discovery immediately and proves latency and attribution on real
   hardware before anything can break connectivity.
2. **Beta — blocking with one-tap allowlist.** Add blocklist matching and the unblock loop.
   Ship with a deliberately conservative default list.
3. **Later — telemetry profile integration** into `DebloatCatalog`, and per-app bypass.

Phase 1 is worth doing on its own merits: it answers "what is this device talking to"
without any risk of breaking the device, and that is the question behind the original ask.

### Open questions

- Blocklist source and update cadence — bundled, fetched, or admin-supplied? Fetching
  introduces a supply-chain surface the project has so far avoided.
- Does the filter run in companion/ArborXR mode, or standalone only?
- Fire OS has no Device Owner, so always-on cannot be enforced there — is a
  user-dismissable VPN acceptable on that profile?
