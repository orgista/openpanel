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
| Verified OpenPanel build | 1.1.31-debug, version code 40 |
| Local artifact name | `openpanel-1.1.31-fire7-12thgen-kfquwi-debug.apk` |
| Verification date | 2026-08-14 |

## UI verification

The Settings Wi-Fi and Bluetooth pages and all eight Admin Panel tabs were
checked on the physical device: Applications, Device Health, Books & Audio,
YouTube, Screen Saver, Kiosk Lock, Bug Log, and Security. At the Fire 7's usable
height, the modal reserves the Fire OS navigation inset, keeps all Admin tabs on
one line, preserves 40 px touch targets, and scrolls panel content independently
without moving the tab bar. The Settings admin footer remains fully visible.

## Fire OS management limits

An already-configured Fire tablet cannot grant Device Owner to OpenPanel without
a factory reset and first-run provisioning. On this profile OpenPanel therefore
uses the supported best-effort controls available to a normal app: launcher/home
redirection through accessibility, device admin where Fire OS permits it,
notification-listener policy, system-UI protection, and Android screen pinning.
The Admin Panel exposes Device Owner status instead of claiming a stronger lock
than the operating system provides.

Fire OS can reserve the bottom 48 px even when CSS reports the full 600 px
physical height. Responsive modal rules must account for that inset; testing at
an ordinary 1024×600 desktop viewport alone is not sufficient.

## Build and publication policy

Use the source build commands in the project README. Debug APKs are local test
artifacts and remain ignored by Git. Signed APKs must come from the protected
release workflow so their package name, version, SDK range, and signing identity
are reproducible and verifiable.
