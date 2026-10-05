package com.devlabs.aulaflix.service;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * Whom a limit counts: one client network, one Student or everyone at once. Equal keys share one counter, and the log
 * names a key by its subject, which never holds an email.
 */
public record RateLimitKey(String subject) {

    private static final RateLimitKey EVERYONE = new RateLimitKey("everyone");

    /**
     * An IPv4 address on its own, and an IPv6 address by its /64, since one subscriber usually holds a whole /64 and
     * picks any address in it. An IPv4 address written as IPv6, {@code ::ffff:203.0.113.7}, is parsed as IPv4.
     */
    public static RateLimitKey clientIp(InetAddress address) {
        if (address instanceof Inet6Address) {
            ByteBuffer network = ByteBuffer.wrap(address.getAddress());
            return new RateLimitKey("IP %x:%x:%x:%x::/64".formatted(Short.toUnsignedInt(network.getShort()),
                    Short.toUnsignedInt(network.getShort()), Short.toUnsignedInt(network.getShort()),
                    Short.toUnsignedInt(network.getShort())));
        }
        return new RateLimitKey("IP " + address.getHostAddress());
    }

    /** By the Student's Account id. */
    public static RateLimitKey student(long accountId) {
        return new RateLimitKey("Student " + accountId);
    }

    /** One key for every request, which catches a flood spread over many IPs. */
    public static RateLimitKey everyone() {
        return EVERYONE;
    }

    @Override
    public String toString() {
        return subject;
    }
}
