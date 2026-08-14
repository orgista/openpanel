package com.orgista.openpanel;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Exact navigation boundary for OpenPanel's escaped-link browser. */
final class SafeBrowserPolicy {
    static final String HOME_URL = "https://www.kiddle.co/";

    private SafeBrowserPolicy() {}

    static boolean isAllowed(String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null) return false;
            String normalized = host.toLowerCase(Locale.ROOT);
            return "kiddle.co".equals(normalized) || normalized.endsWith(".kiddle.co");
        } catch (URISyntaxException error) {
            return false;
        }
    }

    static String initialUrl(String requested) {
        return isAllowed(requested) ? requested : HOME_URL;
    }
}
