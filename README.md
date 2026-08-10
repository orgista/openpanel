# OpenPanel

A managed **kiosk launcher** for Android tablets, Google TV, and XR headsets
(Meta Quest, Pico, Vive Focus, Lenovo, …). One adaptive app presents an approved
catalog of apps and approved YouTube videos, channels, and playlists, and
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
- **Forward-compatible Android build:** no maximum Android version is declared;
  the stable Capacitor 8 toolchain currently compiles and targets API 36 and is
  designed for Android 17/API 37 runtime compatibility without adopting the
  preview target SDK in production. API 37 runtime testing remains part of the
  pre-promotion checklist. See
  [`docs/android-17-tv-readiness.md`](docs/android-17-tv-readiness.md).
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
verification workflow checks out an explicitly pinned private UI revision and
builds a signed APK with external signing inputs, then verifies its package,
SDK range, TV eligibility, and exact production signing-certificate digest.
`android/app/build.gradle` refuses release tasks unless
`OPENPANEL_KEYSTORE_FILE`, `OPENPANEL_KEYSTORE_PASS`,
`OPENPANEL_KEY_ALIAS`, and `OPENPANEL_KEY_PASS` are provided from CI or a secret
manager.

The signing preflight is wired into APK/bundle packaging tasks. A release build
with missing signing inputs fails instead of falling through to an unsigned APK.

## Storage

The canonical project—including the public engine and private `src/` UI
repository—lives in this single NAS folder. OpenPanel's local Gradle helper uses
an automatically cleaned temporary cache because Gradle file locking is not
supported by the SMB share. See [`docs/storage-layout.md`](docs/storage-layout.md)
and run `npm run storage:audit` to verify the layout. Ignored private records,
the local upload keystore, and project-specific agent history are consolidated
under `private/`; their legacy paths are compatibility symlinks.

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
