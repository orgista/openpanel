package com.orgista.openpanel;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Dns;

/**
 * Keeps IPv6 as a fallback while avoiding long connection stalls on older TVs
 * that receive IPv6 DNS answers without having a working IPv6 route.
 */
final class Ipv4FirstDns implements Dns {
    static final Ipv4FirstDns INSTANCE = new Ipv4FirstDns();

    private Ipv4FirstDns() {}

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        return order(Dns.SYSTEM.lookup(hostname));
    }

    static List<InetAddress> order(List<InetAddress> addresses) {
        List<InetAddress> ordered = new ArrayList<>(addresses);
        // List.sort is stable, so DNS preference is retained within each family.
        ordered.sort((left, right) -> Boolean.compare(
            left instanceof Inet6Address,
            right instanceof Inet6Address
        ));
        return ordered;
    }
}
