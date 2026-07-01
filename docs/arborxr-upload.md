# ArborXR Upload Build

OpenPanel ships as a single Capacitor Android app:

- Package: `com.orgista.openpanel`
- Min SDK 25 / Target SDK 34, landscape
- Registers as a HOME launcher (so it can be the device launcher in standalone kiosk mode)
- UI: React (`src/`) bundled into the native shell (`android/`); native bridge in `SystemBridgePlugin`

## Building

The APK is built by **GitHub Actions** (`.github/workflows/android.yml`) — the repo
lives on an SMB share that can't run Gradle locally. To build on a local-disk
checkout:

```sh
npm ci
npm run build            # vite: src/ -> dist/
npx cap sync android     # copy the web bundle + plugins into the native project
cd android && ./gradlew assembleDebug      # debug-signed; for release see below
```

Debug builds are signed with the standard Android debug key — fine for dev,
never trusted for production updates.

## Signing (release)

Release signing material is **not** stored in this repo. CI signs the release APK
with the upload key supplied via repo secrets — `RELEASE_KEYSTORE_BASE64`,
`OPENPANEL_KEYSTORE_PASS`, `OPENPANEL_KEY_PASS`, `OPENPANEL_KEY_ALIAS` (see
`docs/open-source-split.md` for the `gh secret set` commands). To sign locally,
run `./gradlew assembleRelease`, then sign the unsigned APK with `apksigner` using
your keystore kept **outside** the repo (e.g. `~/openpanel-upload.keystore`).

Keep the upload key stable and managed in a secret manager / offline signer:
Android rejects updates signed by a different key. Keystores are git-ignored
(`*.keystore`); never commit one.

## Uploading to ArborXR

Upload the signed APK to ArborXR and deploy it to your device group.

- On **ArborXR-managed** devices, OpenPanel detects ArborXR is the Device Owner
  and runs in **companion** mode — ArborXR handles kiosk lockdown, OpenPanel is
  just the launcher UI.
- On **unmanaged** devices, OpenPanel offers **standalone** mode (set as launcher
  + device admin and lock the device itself) via the first-run prompt and the
  Admin Panel → Kiosk tab.
