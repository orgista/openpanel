package com.orgista.openpanel;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Android 12+ managed provisioning: the setup wizard asks the DPC which
 * provisioning mode to use. OpenPanel standalone kiosks are always fully
 * managed devices (Device Owner), never work profiles.
 */
public class ProvisioningModeActivity extends Activity {
    private static final String EXTRA_PROVISIONING_MODE = "android.app.extra.PROVISIONING_MODE";
    private static final int PROVISIONING_MODE_FULLY_MANAGED_DEVICE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent result = new Intent();
        result.putExtra(EXTRA_PROVISIONING_MODE, PROVISIONING_MODE_FULLY_MANAGED_DEVICE);
        setResult(RESULT_OK, result);
        finish();
    }
}
