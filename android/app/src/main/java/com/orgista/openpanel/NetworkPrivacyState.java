package com.orgista.openpanel;

/** Pure decisions used by the TV DNS-filter status bridge and unit tests. */
final class NetworkPrivacyState {
    static final String DNS_FILTER_PACKAGE = "dnsfilter.android";

    private NetworkPrivacyState() {}

    static String normalizePrivateDnsMode(String mode) {
        if ("off".equals(mode)
                || "opportunistic".equals(mode)
                || "hostname".equals(mode)) {
            return mode;
        }
        return "unknown";
    }

    static boolean isFilterActive(
        boolean installed,
        boolean enabled,
        boolean vpnOwnedByFilter
    ) {
        return installed && enabled && vpnOwnedByFilter;
    }

    static boolean isVpnOwnedByFilter(
        boolean vpnActive,
        boolean ownerUidMatches,
        boolean filterIsAlwaysOn
    ) {
        // Some Fire OS builds redact NetworkCapabilities#getOwnerUid even for
        // the active VPN. Android permits one VPN per user, so an active VPN
        // plus this package being configured as always-on is authoritative.
        return vpnActive && (ownerUidMatches || filterIsAlwaysOn);
    }

    static boolean privateDnsMayBypassFilter(boolean filterActive, String privateDnsMode) {
        return filterActive && ("hostname".equals(privateDnsMode)
            || "opportunistic".equals(privateDnsMode));
    }
}
