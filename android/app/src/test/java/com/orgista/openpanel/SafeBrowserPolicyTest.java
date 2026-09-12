package com.orgista.openpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SafeBrowserPolicyTest {
    @Test
    public void allowsOnlyEncryptedKiddlePages() {
        assertTrue(SafeBrowserPolicy.isAllowed("https://www.kiddle.co/"));
        assertTrue(SafeBrowserPolicy.isAllowed("https://kids.kiddle.co/Dinosaur"));
        assertFalse(SafeBrowserPolicy.isAllowed("http://www.kiddle.co/"));
        assertFalse(SafeBrowserPolicy.isAllowed("https://kiddle.co.example.com/"));
        assertFalse(SafeBrowserPolicy.isAllowed("https://example.com/"));
        assertFalse(SafeBrowserPolicy.isAllowed("file:///sdcard/private.txt"));
    }

    @Test
    public void replacesUnapprovedEscapesWithTheSafeHomePage() {
        assertEquals(
            SafeBrowserPolicy.HOME_URL,
            SafeBrowserPolicy.initialUrl("https://example.com/escaped")
        );
        assertEquals(
            "https://kids.kiddle.co/Space",
            SafeBrowserPolicy.initialUrl("https://kids.kiddle.co/Space")
        );
        assertEquals(SafeBrowserPolicy.HOME_URL, SafeBrowserPolicy.initialUrl(null));
    }
}
