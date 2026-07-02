# ArborXR Upload Build

OpenPanel ships as a single Capacitor Android app:

- Package: `com.orgista.openpanel`
- Min SDK 25 / Target SDK 34, landscape
- Registers as a HOME launcher (so it can be the device launcher in standalone kiosk mode)
- UI: React (`src/`) bundled into the native shell (`android/`); native bridge in `SystemBridgePlugin`

## Building

The checked-in GitHub Actions workflow only verifies that the public native
engine compiles. A full ArborXR APK build needs a UI-aware checkout because the
private React UI lives at `src/`. The repo lives on an SMB share that can break
Gradle locking/cleanup, so use a local-disk checkout or CI runner for release
builds:

```sh
npm ci
npm run build            # vite: src/ -> dist/
npx cap sync android     # copy the web bundle + plugins into the native project
cd android && ./gradlew assembleDebug      # debug-signed; for dev only
```

Debug builds are signed with the standard Android debug key — fine for dev,
never trusted for production updates.

Use JDK 21 for the Android build. Java 17 fails Capacitor's source level 21, and
newer JDKs can fail Android's `jlink` transform. On macOS, Android Studio's
bundled JBR is a convenient JDK 21:
`/Applications/Android Studio.app/Contents/jbr/Contents/Home`.

## Signing (release)

Release signing material is **not** stored in this repo. Release Gradle tasks
now require external signing inputs and fail if they are absent. The preflight is
wired into `preReleaseBuild`, `assembleRelease`, `bundleRelease`, and
`packageRelease`, so missing signing inputs stop the release path before Java
compilation or unsigned APK output.

- `OPENPANEL_KEYSTORE_FILE`
- `OPENPANEL_KEYSTORE_PASS`
- `OPENPANEL_KEY_ALIAS`
- `OPENPANEL_KEY_PASS`

For CI, store the keystore as `RELEASE_KEYSTORE_BASE64`, decode it into a runner
temp file, and export `OPENPANEL_KEYSTORE_FILE` to that path before running
`./gradlew assembleRelease`. For local signing, keep the keystore **outside** the
repo, for example `~/openpanel-upload.keystore`, and pass these values through
your shell or secret manager.

Keep the upload key stable and managed in a secret manager / offline signer:
Android rejects updates signed by a different key. Keystores are git-ignored
(`*.keystore`); never commit one.

Before upload, verify the produced APK:

```sh
apksigner verify --print-certs android/app/build/outputs/apk/release/app-release.apk
aapt dump badging android/app/build/outputs/apk/release/app-release.apk | grep -E "package:|sdkVersion|targetSdkVersion"
```

## Uploading to ArborXR

Upload the signed APK to ArborXR and deploy it to your device group.

- On **ArborXR-managed** devices, OpenPanel detects ArborXR is the Device Owner
  and runs in **companion** mode — ArborXR handles kiosk lockdown, OpenPanel is
  just the launcher UI.
- On **unmanaged** devices, OpenPanel offers **standalone** mode (set as launcher
  + device admin and lock the device itself) via the first-run prompt and the
  Admin Panel → Kiosk tab.
