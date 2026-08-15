# OpenPanel

A managed **kiosk launcher** for Android tablets, Google TV, and XR headsets
(Meta Quest, Pico, Vive Focus, Lenovo, …). One adaptive app presents an approved
catalog of apps and approved YouTube videos, channels, and playlists, and
an offline books/audiobooks shelf with standards-based institutional catalogs,
confines the device — either
alongside **ArborXR** (companion mode) or on its own (standalone kiosk).

- **Package:** `com.orgista.openpanel`
- **One APK, all form factors:** phone/tablet (`LAUNCHER`), Google TV
  (`LEANBACK_LAUNCHER` + banner), and XR headsets (touchscreen not required, runs
  as a 2D panel — the most compatible XR approach across every ArborXR-managed
  headset).
- **Restricted YouTube sources:** admins can approve individual videos,
  playlists, canonical channel URLs, modern `@handle` channel URLs, and legacy
  channel usernames without configuring a YouTube API key. Channel names and
  handles are verified to a canonical channel ID before they can be added.
  Channel tiles open a recent-video browser backed by YouTube's public channel
  feed, allowing the user to choose a video before it opens in the restricted
  privacy-domain player.
- **Google TV controls:** the app detects TV/D-pad input, exposes the connected
  controller in the Admin Panel, uses a keyboard-free remote PIN keypad, and
  delegates speech recognition to the system Google TV/Gboard experience
  without requesting microphone permission.
- **Device Health:** detects the device manufacturer/model and Android version,
  reports live RAM and storage use, and applies a conservative OEM-aware
  debloat policy in standalone Device Owner mode. Package changes are hidden
  reversibly and can be restored from the Admin Panel. Notification management
  is enabled by default on Android 13+ and restores only grants previously
  changed by OpenPanel. See
  [`docs/device-health-debloater.md`](docs/device-health-debloater.md).
- **TV DNS & telemetry status:** the TV-only Device Health view detects
  personalDNSfilter, its active VPN owner, always-on/lockdown state, and Android
  Private DNS conflicts. Standalone Device Owner installations can keep the
  filter always-on without risking a network-lockdown outage; ArborXR-managed
  TVs remain report-only. See [`docs/tv-dns-filter.md`](docs/tv-dns-filter.md).
- **Books & Audio:** imports EPUB, PDF, Readium audiobook, MP3, and AAC files;
  reads OPDS 1.2/2.0 catalogs without WebView CORS restrictions; stores
  publications privately for offline use; and resumes reading/listening with
  Readium navigators. Open-access and institutional catalogs are added by an
  administrator after deployment; none are bundled as content defaults.
  Licensed lending systems still require the institution's authorized
  authentication/DRM connector. See [`docs/library-opds-readium.md`](docs/library-opds-readium.md).
- **Forward-compatible Android build:** no maximum Android version is declared;
  the stable Capacitor 8 toolchain currently compiles and targets API 36 and is
  designed for Android 17/API 37 runtime compatibility without adopting the
  preview target SDK in production. API 37 runtime testing remains part of the
  pre-promotion checklist. See
  [`docs/android-17-tv-readiness.md`](docs/android-17-tv-readiness.md).
- **Two management modes**, auto-detected on first run:
  - **Companion** — ArborXR is the Device Owner and handles lockdown; OpenPanel is
    the launcher UI on compatible non-Fire devices. (Auto-selected when
    `app.xrdm.client` is the Device Owner.)
  - **Standalone** — OpenPanel becomes the HOME launcher and locks the device
    itself (device admin + lock task / screen pinning) for setups without ArborXR.
  - **Fire OS** — always uses standalone Home/accessibility redirection; ArborXR
    and Device Owner enrollment are not offered in the Fire UI. Generic managed
    provisioning instructions live in
    [`docs/device-owner-provisioning.md`](docs/device-owner-provisioning.md).
- **Verified Fire tablet profile:** Fire 7 (2022, 12th Generation), Amazon model
  `KFQUWI` / codename `quartz`, running Fire OS 8 (Android 11 / API 30). The
  launcher and every Admin Panel tab are tested in reverse landscape at the
  device's 1024×552 usable app viewport. Fire builds force the standalone
  redirect kiosk and omit ArborXR/Device Owner setup. See
  [`docs/fire-tablet-support.md`](docs/fire-tablet-support.md).
- **Child-safe app and web boundary:** storefronts, recovery launchers,
  unrestricted browsers, and background admin utilities can stay installed but
  never appear as child-facing launcher tiles. Escaped HTTP(S) links can be
  assigned to OpenPanel's non-catalog Safe Browser, which permits only encrypted
  `kiddle.co` pages and blocks navigation away from that domain. These rules are
  shared by Fire, generic Android tablet, TV, and XR builds. See
  [`docs/child-safe-browser.md`](docs/child-safe-browser.md).

## Open-source structure

OpenPanel is MIT-licensed and split into two source repositories so the native
engine and launcher UI can be versioned independently:

- **This repo (public)** — the engine: the Capacitor **native bridge**
  (`SystemBridgePlugin`: apps, Wi-Fi, Bluetooth, kiosk lock, device admin,
  ArborXR detection; `LibraryBridgePlugin`: OPDS, downloads, and local imports),
  the Android project (`android/`), build config, and CI.
- **The React UI (public MIT mirror)** — the polished launcher UI is maintained
  separately at `cyberbanksy/openpanel-ui` and mounted at **`src/`**
  (git-ignored here). See
  [`docs/open-source-split.md`](docs/open-source-split.md).

## Build

The full APK build needs the UI repository present at `src/`. With it in place:

```sh
npm ci
npm test                 # UI regression tests
npm run typecheck        # TypeScript validation
npm run build            # vite: src/ -> dist/
npx cap sync android     # copy the web bundle + plugins into the native project
./scripts/gradle-local.sh :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Android native compilation requires JDK 21. Java 17 fails Capacitor's source
level 21, and newer JDKs can fail Android's `jlink` transform. Set `JAVA_HOME`
to a real JDK 21 install before running Gradle locally.

The engine CI (`.github/workflows/android.yml`) runs native tests, lint, debug
assembly, and instrumentation-test compilation. The protected manual release
verification workflow checks out an explicitly pinned UI revision and
builds a signed APK with external signing inputs, then verifies its package,
SDK range, TV eligibility, and exact production signing-certificate digest.
`android/app/build.gradle` refuses release tasks unless
`OPENPANEL_KEYSTORE_FILE`, `OPENPANEL_KEYSTORE_PASS`,
`OPENPANEL_KEY_ALIAS`, and `OPENPANEL_KEY_PASS` are provided from CI or a secret
manager.

The signing preflight is wired into APK/bundle packaging tasks. A release build
with missing signing inputs fails instead of falling through to an unsigned APK.

### Verified Fire build

| OpenPanel | Fire tablet | Fire OS / Android | Orientation and usable viewport | Local debug artifact |
| --- | --- | --- | --- | --- |
| 1.1.31 (40) | Fire 7 (2022, 12th Gen), `KFQUWI` / `quartz` | Fire OS 8, Android 11 (API 30), build `RS8338.3339N` | Reverse landscape, 1024×552 | `openpanel-1.1.31-fire7-12thgen-kfquwi-debug.apk` |

APK files stay ignored and are not committed to source control. Release APKs
must be reproduced by the signed release workflow. The local debug artifact is
only an installation/test result for the authorized physical tablet.

## Storage

The canonical project—including the engine and `src/` UI repository—lives in
this single NAS folder. OpenPanel's local Gradle helper uses
an automatically cleaned temporary cache because Gradle file locking is not
supported by the SMB share. See [`docs/storage-layout.md`](docs/storage-layout.md)
and run `npm run storage:audit` to verify the layout. Ignored private records,
the local upload keystore, and project-specific agent history are consolidated
under `private/`; their legacy paths are compatibility symlinks.

## Deploy

For compatible non-Fire devices, upload the signed APK to **ArborXR** and assign
it to a device/group. On ArborXR-managed devices OpenPanel runs in companion
mode automatically; on unmanaged devices, use the standalone kiosk flow. Fire
tablets use the Fire standalone flow and are not ArborXR deployment targets in
this project. See
[`docs/arborxr-upload.md`](docs/arborxr-upload.md).


## Security

- No signing keys or secrets in source (keys are git-ignored; release signing
  material lives in CI secrets / a secret manager).
- App backup disabled; admin PIN / recovery stored as salted PBKDF2 verifiers
  with persistent lockout; privileged Wi-Fi/Bluetooth/settings actions are
  admin-gated.
- Debloating uses reviewed exact package names with hard protections for core
  Android, emergency, launcher, wallpaper, OTA, DPC/ArborXR, and OpenPanel
  packages. There are no package-name wildcards and no self-ADB or root shell.

## License

MIT — see [LICENSE](LICENSE). © 2026 Orgista.
