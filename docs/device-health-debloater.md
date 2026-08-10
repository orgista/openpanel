# Device Health and debloat policy

OpenPanel's Device Health engine is an MIT-licensed, auditable Android Device
Owner policy. The reviewed package catalog lives at
[`android/app/src/main/java/com/orgista/openpanel/DebloatCatalog.java`](../android/app/src/main/java/com/orgista/openpanel/DebloatCatalog.java).

## What it does

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

## Why it does not run ADB

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

## Safety boundaries

The catalog never pattern-matches unknown packages. It hard-protects core
Android services, Settings, System UI, phone/emergency components, permission
and package installers, Google Play services/framework, current launchers,
wallpaper providers, Lenovo OTA/management agents, ArborXR's DPC and launcher,
and OpenPanel's production/debug packages.

Apps disabled with ADB, an OEM tool, or a different DPC are shown as **Disabled
outside OpenPanel**. OpenPanel does not claim it can restore changes it did not
make. Restore actions only reverse the OpenPanel-tracked hidden-package and
notification sets.

## Adding or reviewing a package

1. Confirm the package name on a device you own or are authorized to manage.
2. Identify the exact user-facing purpose and the applicable OEM profile.
3. Verify it is not required for boot, setup, emergency use, the launcher,
   wallpaper, OTA, accessibility, permissions, or device management.
4. Add one exact `rule(...)` entry to `DebloatCatalog.java`.
5. Add or update a unit test in
   `android/app/src/test/java/com/orgista/openpanel/DebloatCatalogTest.java`.
6. Run `./scripts/gradle-local.sh :app:testDebugUnitTest :app:lintDebug` and test
   hide plus restore on the applicable device before promotion.

## License

This engine and policy catalog are available under the repository's
[MIT License](../LICENSE). The license permits use, copying, modification,
distribution, sublicensing, and commercial use while retaining the copyright
and license notice; the software is provided without warranty.
