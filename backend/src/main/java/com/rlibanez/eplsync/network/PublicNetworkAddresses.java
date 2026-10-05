package com.rlibanez.eplsync.network;

import java.net.InetAddress;
import java.util.Arrays;

public final class PublicNetworkAddresses {
    private PublicNetworkAddresses() {}
    // Conservatively classify special-purpose space, including IPv4-mapped IPv6 and transition mechanisms.
    // Registries: https://www.iana.org/assignments/iana-ipv4-special-registry/
    //             https://www.iana.org/assignments/iana-ipv6-special-registry/
    public static boolean isPublicAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            boolean mapped = true;
            for (int i=0;i<10;i++) mapped &= bytes[i] == 0;
            if (mapped && bytes[10] == (byte)0xff && bytes[11] == (byte)0xff)
                return publicIpv4(Arrays.copyOfRange(bytes,12,16));
            if ((bytes[0] & 0xe0) != 0x20) return false; // Currently allocated global unicast: 2000::/3.
            if (bytes[0] == 0x20 && bytes[1] == 0x01) {
                if ((bytes[2] & 0xfe) == 0) return false; // IANA special-purpose 2001::/23.
                if ((bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8) return false; // Documentation.
            }
            if (bytes[0] == 0x20 && bytes[1] == 0x02) return false; // 6to4 can embed internal IPv4.
            if (bytes[0] == 0x3f && (bytes[1] & 0xff) == 0xff && (bytes[2] & 0xf0) == 0) return false; // Documentation 3fff::/20.
            return !(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress());
        }
        return bytes.length == 4 && publicIpv4(bytes);
    }
    private static boolean publicIpv4(byte[] bytes) {
        int a=bytes[0]&255, b=bytes[1]&255, c=bytes[2]&255;
        return !(a == 0 || a == 10 || a == 127 || a >= 224
            || (a == 100 && b >= 64 && b <= 127)
            || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
            || (a == 192 && ((b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99) || b == 168))
            || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
            || (a == 203 && b == 0 && c == 113));
    }
}
