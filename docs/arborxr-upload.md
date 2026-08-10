# ArborXR Upload Build

OpenPanel ships as a single Capacitor Android app:

- Package: `com.orgista.openpanel`
- Min SDK 24 / Target SDK 36, landscape
- Registers as a HOME launcher (so it can be the device launcher in standalone kiosk mode)
- UI: React (`src/`) bundled into the native shell (`android/`); native bridge in `SystemBridgePlugin`

## Building

The engine workflow tests, lints, and assembles the public native project. The
protected release-verification workflow also checks out a pinned private UI
revision and verifies a signed release. The repo lives on an SMB share that can
break Gradle locking/cleanup, so use the local helper (which puts Gradle caches
on local temporary storage), a local-disk checkout, or CI for release builds:

```sh
npm ci
npm test
npm run typecheck
npm run build            # vite: src/ -> dist/
npx cap sync android     # copy the web bundle + plugins into the native project
./scripts/gradle-local.sh :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug builds are signed with the standard Android debug key — fine for dev,
never trusted for production updates.

Use JDK 21 for the Android build. Java 17 fails Capacitor's source level 21, and
newer JDKs can fail Android's `jlink` transform. On macOS, Android Studio's
bundled JBR is a convenient JDK 21:
`/Applications/Android Studio.app/Contents/jbr/Contents/Home`.

## Signing (release)

Release signing material is **not** stored in this repo. Release packaging tasks
require external signing inputs and fail instead of producing an unsigned APK.

- `OPENPANEL_KEYSTORE_FILE`
- `OPENPANEL_KEYSTORE_PASS`
- `OPENPANEL_KEY_ALIAS`
- `OPENPANEL_KEY_PASS`

For CI, store the keystore as `RELEASE_KEYSTORE_BASE64`, decode it into a runner
temp file, and export `OPENPANEL_KEYSTORE_FILE` to that path before running
`./gradlew assembleRelease`. For local signing, use the ignored, owner-only NAS
file at
`/Volumes/Files/Projects/Code, Apps & Websites/Apps/OpenPanel/private/signing/openpanel-production.keystore`
and retrieve its password from macOS Keychain. The old
`openpanel-upload.keystore` file and its compatibility symlink are retained only
as legacy records; they cannot sign releases made with the replacement key.

Keep the upload key stable and managed in a secret manager / offline signer:
Android rejects updates signed by a different key. Keystores are git-ignored
(`*.keystore`); never commit one.

The legacy signing password appeared in local agent history and was redacted.
The replacement production key uses a distinct password stored in Keychain and
in an independent owner-controlled recovery vault.

Before upload, verify the produced APK:

```sh
apksigner verify --print-certs android/app/build/outputs/apk/release/app-release.apk
aapt dump badging android/app/build/outputs/apk/release/app-release.apk | grep -E "package:|sdkVersion|targetSdkVersion"
```

For local releases, `scripts/build-release-keychain.sh` retrieves the password
from macOS Keychain, runs native tests and lint, builds the signed APK, verifies
its identity, and writes a SHA-256 checksum without exposing the password in
shell history or Gradle properties.

The protected `Full Release Verification` workflow performs these checks
without publishing or uploading the APK. Configure `OPENPANEL_UI_REF` as a full
private UI commit SHA, `UI_SUBMODULE_TOKEN` for read-only UI access, and the
release signing secrets before dispatching it. Set the protected environment
variable `OPENPANEL_SIGNING_CERT_SHA256` to the production certificate's
SHA-256 digest (as printed by `apksigner`) so a wrong or rotated key cannot
silently produce an incompatible update.

## Uploading to ArborXR

Use the single existing ArborXR app entry for `com.orgista.openpanel`. Do not
create a separate app entry or package for debug/testing deployments. All
signed builds use the production signing lineage and are separated by release
channels on the same app entry.

ArborXR's special `Latest` channel automatically advances when a newer APK is
uploaded, including an upload explicitly associated with another channel. It
must therefore not be assigned to production groups when releases require a
test-before-promotion gate. Use this channel layout instead:

- `Production`: pinned to the last approved build and assigned to production
  groups.
- `Debug`: points to the candidate build and is assigned only to named test
  devices.
- `Latest`: automatic ArborXR channel; leave it unassigned.

Promote only after device testing passes by changing `Production` to the
already-uploaded candidate build. Before every upload, audit all group and
device assignments and fail if `Latest` or `Debug` is assigned outside its
intended scope.

The guarded release helper enforces that sequence and verifies the APK package,
version, checksum, signer, release-channel identities, test-device identity,
and all OpenPanel group/device assignments before it changes remote state:

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

- On **ArborXR-managed** devices, OpenPanel detects ArborXR is the Device Owner
  and runs in **companion** mode — ArborXR handles kiosk lockdown, OpenPanel is
  just the launcher UI.
- On **unmanaged** devices, OpenPanel offers **standalone** mode (set as launcher
  + device admin and lock the device itself) via the first-run prompt and the
  Admin Panel → Kiosk tab.
