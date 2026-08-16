# Managed Android Provisioning

OpenPanel keeps managed-device enrollment instructions in the repository rather
than displaying a provisioning QR on the target device. A QR shown by that
tablet is unavailable after the factory reset that managed provisioning needs.

These instructions are for compatible generic Android tablets, Google TV, and
XR deployments. The OpenPanel Fire profile deliberately does not offer ArborXR
or Device Owner enrollment; use the Fire standalone procedure in
[`fire-tablet-support.md`](fire-tablet-support.md).

## Development provisioning with ADB

Android only accepts a Device Owner while the device has no accounts and is not
already managed. Use a fresh test device or remove accounts and users as allowed
by that device's Android build, install the intended APK, and then run exactly
one of these commands from an authorized workstation:

```sh
# Debug build
adb shell dpm set-device-owner \
  com.orgista.openpanel.debug/com.orgista.openpanel.OpenPanelDeviceAdminReceiver

# Signed production build
adb shell dpm set-device-owner \
  com.orgista.openpanel/com.orgista.openpanel.OpenPanelDeviceAdminReceiver
```

Verify the result before deployment:

```sh
adb shell dpm list-owners
adb shell dumpsys device_policy
```

Do not run `set-device-owner` against a personal device or a tablet that already
contains user data. Removing Device Owner changes device-management state and a
failed or incompatible provisioning attempt can require another factory reset.

## QR enrollment on compatible generic Android

If a supported device uses Android's setup-wizard QR enrollment, create and
save the enrollment QR on a separate administrator computer or phone **before**
resetting the target. The payload must identify OpenPanel's device-admin
component, a reachable HTTPS APK download, and the signing-certificate checksum
for that exact APK. Keep the QR with the deployment record; never rely on a copy
displayed inside the target app.

QR support is controlled by the device's setup wizard and OEM management stack.
If the setup wizard does not expose managed QR enrollment, use the supported
EMM/DPC workflow for that device or the ADB development procedure above. An
installed Android app cannot promote itself to Device Owner.

As of 1.1.43 the app declares the Android 12+ provisioning handlers
(`GET_PROVISIONING_MODE`, `ADMIN_POLICY_COMPLIANCE`) the setup wizard requires;
earlier builds abort QR enrollment on modern Android.

### QR payload

```json
{
 "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
   "com.orgista.openpanel/com.orgista.openpanel.OpenPanelDeviceAdminReceiver",
 "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "<HTTPS APK URL>",
 "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": "<base64url of signing-cert SHA-256>",
 "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true
}
```

- Checksum for the production key (cert `94323ad0…`):
  `lDI60CvhV8aBJgXvlw9BPy8Vcet7I83IkkH4EW6Zuww` — derive with
  `echo <cert-sha256-hex> | xxd -r -p | base64 | tr '+/' '-_' | tr -d '='`.
- For the download URL, the ArborXR version `downloadUrl`
  (`GET /api/v3/apps/{appId}/versions`) works; it is a signed link that can
  expire, so regenerate the QR if the wizard reports a download failure.
- Render the minified JSON as a QR (any generator; error correction M).
- Enrollment: factory-reset target → tap the welcome screen six times → join
  Wi-Fi when prompted → scan → the wizard downloads the APK, verifies the
  checksum, and sets OpenPanel as Device Owner.

## Fire tablets

Fire builds force OpenPanel into standalone mode. They use the protected Amazon
launcher plus OpenPanel's accessibility Home redirect, device admin,
notification blocking, overlay/usage access, and reversible ADB configuration.
The Admin Panel intentionally contains no ArborXR selector, Device Owner status
step, or on-device enrollment QR.
