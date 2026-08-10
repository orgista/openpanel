# Android 17 and Google TV readiness

OpenPanel uses the latest stable Capacitor 8 Android toolchain: compile SDK 36,
target SDK 36, and minimum SDK 24. The manifest does not set `maxSdkVersion`, so
the APK remains installable on Android 17/API 37 devices. Android 17 is still a
beta platform as of July 2026, so the production build keeps the stable API 36
target while its all-app behavior changes are reviewed and tested. Target SDK
37 should be adopted only after Android 17 and the supporting Capacitor/Android
Gradle toolchain are stable; prerelease framework dependencies are intentionally
excluded from production.

Current readiness work includes:

- adaptive tablet and TV layouts in landscape and portrait;
- launcher and Leanback launcher declarations in one APK;
- a 16:9 TV banner and optional touchscreen/faketouch hardware;
- Google TV/D-pad controller detection and system speech recognition;
- a keyboard-free PIN keypad with explicit D-pad focus movement;
- API-key-free exact YouTube channel verification using unique handles and
  canonical channel IDs, plus a recent-video channel browser and direct video
  and playlist URLs;
- D-pad-visible focus states and a close-first restricted YouTube player;
- keep-screen-awake behavior only while YouTube is open;
- no bundled native shared libraries, avoiding native 64-bit/16 KB page-size
  compatibility risks;
- backup disabled and no maximum supported Android version.

Before raising `targetSdkVersion` to 37, repeat the web interaction suite, Android
lint/unit tests, on-device instrumentation, Google TV D-pad navigation, ArborXR
lock-task behavior, signed upgrade verification, and a full API 37 emulator or
physical-device pass. The current workstation has the API 37.1 platform files
but not an Android 17 runtime image, so installability is build-reviewed rather
than claimed as physical API 37 validation.
