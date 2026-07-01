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
cd android && ./gradlew assembleDebug     # debug-signed; release signing in CI
```

CI (`.github/workflows/android.yml`) builds a debug APK on every push and a
signed **release** APK on a `v*` tag.

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
