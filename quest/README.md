# OpenPanel VR for Meta Quest

An immersive Horizon OS launcher built with Meta Spatial SDK. The application runs as a
native VR activity and places its app grid on a panel inside a Spatial Editor-authored 3D
scene. It uses the separate Android package `com.orgista.openpanel.quest`, so it can be
managed independently from the tablet build in ArborXR.

## Build

Requirements: JDK 17, Android SDK 34, and Meta Spatial Editor 16.1.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

On `/Volumes/Files`, Gradle can fail while locking cache files or deleting
incremental package output. Use the Codex helper when validating from that
mounted volume:

```sh
scripts/verify
```

Useful quick-context helpers:

```sh
scripts/codex-context
scripts/status-json
```

## Scene ownership

Static panels and 3D geometry live in `app/scenes/Main.metaspatial` and are edited with
Meta Spatial Editor. Installed-app discovery and card contents are generated at runtime.
