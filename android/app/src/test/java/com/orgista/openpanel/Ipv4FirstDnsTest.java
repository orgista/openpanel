package com.orgista.openpanel;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

public class Ipv4FirstDnsTest {
    @Test
    public void ordersIpv4BeforeIpv6AndRetainsFamilyOrder() throws Exception {
        InetAddress ipv6First = InetAddress.getByAddress(new byte[] {
            0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
        });
        InetAddress ipv4First = InetAddress.getByAddress(new byte[] { 10, 0, 0, 1 });
        InetAddress ipv6Second = InetAddress.getByAddress(new byte[] {
            0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2
        });
        InetAddress ipv4Second = InetAddress.getByAddress(new byte[] { 10, 0, 0, 2 });

        List<InetAddress> ordered = Ipv4FirstDns.order(Arrays.asList(
            ipv6First,
            ipv4First,
            ipv6Second,
            ipv4Second
        ));

        assertEquals(
            Arrays.asList(ipv4First, ipv4Second, ipv6First, ipv6Second),
            ordered
        );
    }
}
