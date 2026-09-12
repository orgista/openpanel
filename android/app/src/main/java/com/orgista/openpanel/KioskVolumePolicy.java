package com.orgista.openpanel;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.view.KeyEvent;

/** Keeps the media stream at a consistent child-safe level for an enabled Fire kiosk. */
final class KioskVolumePolicy {
    private static final int TARGET_PERCENT = 28;

    private KioskVolumePolicy() {}

    static boolean shouldLock(String mode, boolean kioskEnabled, boolean fireDevice) {
        return KioskState.MODE_STANDALONE.equals(mode) && kioskEnabled && fireDevice;
    }

    static boolean shouldLock(Context context) {
        return shouldLock(
            KioskState.getMode(context),
            KioskState.isEnabled(context),
            LandscapeOrientationLock.isFireDevice(Build.MANUFACTURER, Build.BRAND)
        );
    }

    static boolean isVolumeMutationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP
            || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    static int targetVolumeForMaximum(int maximum) {
        if (maximum <= 0) return 0;
        return Math.max(1, (maximum * TARGET_PERCENT + 50) / 100);
    }

    static boolean enforceTarget(Context context) {
        if (!shouldLock(context)) return false;
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return false;
        int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int target = targetVolumeForMaximum(maximum);
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != target) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        }
        return true;
    }
}
