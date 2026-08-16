package com.orgista.openpanel;

import android.app.Activity;
import android.os.Bundle;

/**
 * Android 12+ managed provisioning: invoked after Device Owner is set so the
 * DPC can apply initial policy before the setup wizard finishes. OpenPanel
 * performs its kiosk setup in-app (mode setup + admin PIN) on first launch,
 * so this simply reports compliance and lets setup complete.
 */
public class AdminPolicyComplianceActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_OK);
        finish();
    }
}
