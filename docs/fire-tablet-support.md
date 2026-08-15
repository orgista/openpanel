# Amazon Fire Tablet Support

OpenPanel has a physical-device verification profile for the Amazon Fire 7
(2022, 12th Generation). Amazon identifies this tablet by model `KFQUWI`; the
Android device, product, and build codename is `quartz`. See Amazon's
[Fire tablet identification guide](https://developer.amazon.com/docs/device-specs/ft-identify-tablet-devices.html).

## Verified device and build

| Field | Verified value |
| --- | --- |
| Tablet | Amazon Fire 7 (2022, 12th Generation) |
| Model / codename | `KFQUWI` / `quartz` |
| Fire OS | Fire OS 8, build `RS8338.3339N` |
| Android base | Android 11, API 30 |
| Physical display | 600×1024 at 160 dpi |
| OpenPanel orientation | Reverse landscape (`user_rotation=3`) |
| OpenPanel app viewport | 1024×552 after Fire OS's 48 px navigation inset |
| Verified OpenPanel build | 1.1.37-debug, version code 46 |
| Local artifact name | `openpanel-1.1.37-fire-kids-kiosk-debug.apk` |
| Verification date | 2026-08-14 |

## UI verification

The Settings Wi-Fi and Bluetooth pages and all eight Admin Panel tabs were
checked on the physical device: Applications, Device Health, Books & Audio,
YouTube, Screen Saver, Kiosk Lock, Bug Log, and Security. At the Fire 7's usable
height, the modal reserves the Fire OS navigation inset, keeps all Admin tabs on
one line, preserves 40 px touch targets, and scrolls panel content independently
without moving the tab bar. The Settings admin footer remains fully visible.

## Fire OS management limits

The Fire profile always selects standalone mode and does not show ArborXR or
Device Owner enrollment. OpenPanel uses the controls available to a normal Fire
app: launcher/Home redirection through accessibility, device admin where Fire OS
permits it, notification-listener policy, overlay and usage access, system-UI
protection, and a foreground-return loop. Enabling **Fire kiosk** records this
redirect kiosk state without triggering Fire OS's unreliable screen-pinning
prompt.

This is intentionally described as a Fire standalone kiosk rather than Android
Device Owner. Managed Android provisioning remains available for compatible
non-Fire devices and is documented separately in
[`device-owner-provisioning.md`](device-owner-provisioning.md). The target tablet
does not generate an enrollment QR that would disappear during a factory reset.

Fire OS can reserve the bottom 48 px even when CSS reports the full 600 px
physical height. Responsive modal rules must account for that inset; testing at
an ordinary 1024×600 desktop viewport alone is not sufficient.

## Game display compatibility

Some game APKs declare their launcher activity as non-resizable. Fire OS 8 then
uses Android size-compatibility mode when the tablet is locked to landscape,
rendering the game in a roughly 600×351 window with large black borders. Apply
the following device-provisioning settings after confirming that ADB is pointed
at model `KFQUWI`, not another wireless Android device:

```sh
adb -s "$ADB_SERIAL" shell settings put global force_resizable_activities 1
adb -s "$ADB_SERIAL" shell wm set-user-rotation lock 3
adb -s "$ADB_SERIAL" shell wm set-fix-to-user-rotation disabled
```

Confirm the target and effective settings with:

```sh
adb -s "$ADB_SERIAL" shell getprop ro.product.model
adb -s "$ADB_SERIAL" shell getprop ro.serialno
adb -s "$ADB_SERIAL" shell settings get global force_resizable_activities
adb -s "$ADB_SERIAL" shell dumpsys window displays
```

The resizable-activity override lets games use the full display instead of a
small size-compatibility window. Disabling fixed-to-user rotation allows a
portrait-only game to rotate into the full 600×1024 portrait display. OpenPanel
and landscape-native games return to reverse landscape. Do not patch and
re-sign third-party APKs to change their declared orientation.

## Build and publication policy

Use the source build commands in the project README. Debug APKs are local test
artifacts and remain ignored by Git. Signed APKs must come from the protected
release workflow so their package name, version, SDK range, and signing identity
are reproducible and verifiable.
