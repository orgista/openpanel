# OpenPanel

A managed **kiosk launcher** for Android tablets, Google TV, and XR headsets
(Meta Quest, Pico, Vive Focus, Lenovo, …). One adaptive app presents an approved
catalog of apps and locked YouTube videos, and confines the device — either
alongside **ArborXR** (companion mode) or on its own (standalone kiosk).

- **Package:** `com.orgista.openpanel`
- **One APK, all form factors:** phone/tablet (`LAUNCHER`), Google TV
  (`LEANBACK_LAUNCHER` + banner), and XR headsets (touchscreen not required, runs
  as a 2D panel — the most compatible XR approach across every ArborXR-managed
  headset).
- **Two management modes**, auto-detected on first run:
  - **Companion** — ArborXR is the Device Owner and handles lockdown; OpenPanel is
    the launcher UI. (Auto-selected when `app.xrdm.client` is the Device Owner.)
  - **Standalone** — OpenPanel becomes the HOME launcher and locks the device
    itself (device admin + lock task / screen pinning) for setups without ArborXR.

## Open-core structure

This is an **open-core** project:

- **This repo (public)** — the engine: the Capacitor **native bridge**
  (`SystemBridgePlugin`: apps, Wi-Fi, Bluetooth, kiosk lock, device admin,
  ArborXR detection), the Android project (`android/`), build config, and CI.
- **The React UI (private)** — the polished launcher UI is maintained in a
  separate private repo and mounted at **`src/`** (git-ignored here). This repo
  intentionally does **not** contain the UI. See
  [`docs/open-source-split.md`](docs/open-source-split.md).

## Build

The full APK build needs the private UI present at `src/`. With it in place:

```sh
npm ci
npm run build            # vite: src/ -> dist/
npx cap sync android     # copy the web bundle + plugins into the native project
cd android && ./gradlew assembleDebug     # debug-signed; local/dev only
```

Android native compilation requires JDK 21. Java 17 fails Capacitor's source
level 21, and newer JDKs can fail Android's `jlink` transform. Set `JAVA_HOME`
to a real JDK 21 install before running Gradle locally.

The checked-in CI (`.github/workflows/android.yml`) verifies that the public
native engine compiles. A full ArborXR release build needs a UI-aware checkout
and external signing inputs; `android/app/build.gradle` now refuses release
tasks unless `OPENPANEL_KEYSTORE_FILE`, `OPENPANEL_KEYSTORE_PASS`,
`OPENPANEL_KEY_ALIAS`, and `OPENPANEL_KEY_PASS` are provided from CI or a secret
manager.

The signing preflight is wired into `preReleaseBuild`, `assembleRelease`,
`bundleRelease`, and `packageRelease`. A release build with missing signing
inputs fails before Java compilation instead of falling through to unsigned APK
output or a later toolchain error.

## Deploy

Upload the signed APK to **ArborXR** and assign it to a device/group. On
ArborXR-managed devices OpenPanel runs in companion mode automatically; on
unmanaged devices, use the standalone kiosk flow. See
[`docs/arborxr-upload.md`](docs/arborxr-upload.md).

## Security

- No signing keys or secrets in source (keys are git-ignored; release signing
  material lives in CI secrets / a secret manager).
- App backup disabled; admin PIN / recovery stored as salted PBKDF2 verifiers
  with persistent lockout; privileged Wi-Fi/Bluetooth/settings actions are
  admin-gated.

## License

MIT — see [LICENSE](LICENSE). © 2026 Orgista.
