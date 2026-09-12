package com.orgista.openpanel;

/** API-level permission rules kept independent of Android framework state for unit testing. */
final class BluetoothAccess {
    private static final int ANDROID_12_API = 31;

    private BluetoothAccess() {}

    static boolean canReadAdapterState(int sdkInt, boolean connectPermissionGranted) {
        return sdkInt < ANDROID_12_API || connectPermissionGranted;
    }
}
