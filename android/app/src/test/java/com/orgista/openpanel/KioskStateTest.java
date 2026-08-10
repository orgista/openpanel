package com.orgista.openpanel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KioskStateTest {
    @Test
    public void autoPinRequiresStandaloneModeAndExplicitEnablement() {
        assertTrue(KioskState.shouldAutoPin("standalone", true));
        assertFalse(KioskState.shouldAutoPin("standalone", false));
        assertFalse(KioskState.shouldAutoPin("companion", true));
    }
}
