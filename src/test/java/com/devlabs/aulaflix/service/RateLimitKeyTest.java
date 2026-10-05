package com.devlabs.aulaflix.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.PropertyChecker;
import org.junit.jupiter.api.Test;

/**
 * One subscriber usually holds a whole IPv6 /64 and picks any address in it, so an IPv6 client is counted by its /64;
 * an IPv4 client, by its address.
 */
class RateLimitKeyTest {

    /** The last 64 bits of an IPv6 address, in four 16-bit groups. */
    private static final Generator<List<Integer>> INTERFACE_IDS = Generator.from(data -> IntStream.range(0, 4)
            .mapToObj(group -> data.generate(Generator.integers(0, 0xffff)))
            .toList());

    /**
     * The first 64 bits. Not all zeros: {@code ::ffff:0:0/96}, where IPv4 addresses are written as IPv6, lies in that
     * /64, and those addresses count as their IPv4 address.
     */
    private static final Generator<List<Integer>> NETWORKS =
            INTERFACE_IDS.suchThat(groups -> groups.stream().anyMatch(group -> group != 0));

    @Test
    void givesEveryAddressInAnIpv6Slash64TheSameKey() {
        PropertyChecker.forAll(Generator.from(data -> new SameNetwork(data.generate(NETWORKS),
                data.generate(INTERFACE_IDS), data.generate(INTERFACE_IDS))), pair ->
                RateLimitKey.clientIp(address(pair.network(), pair.oneInterface()))
                        .equals(RateLimitKey.clientIp(address(pair.network(), pair.otherInterface()))));
    }

    @Test
    void givesAddressesInDifferentIpv6Slash64sDifferentKeys() {
        PropertyChecker.forAll(Generator.from(data -> new TwoNetworks(data.generate(NETWORKS),
                                data.generate(NETWORKS), data.generate(INTERFACE_IDS)))
                        .suchThat(pair -> !pair.one().equals(pair.other())),
                pair -> !RateLimitKey.clientIp(address(pair.one(), pair.interfaceId()))
                        .equals(RateLimitKey.clientIp(address(pair.other(), pair.interfaceId()))));
    }

    @Test
    void namesAnIpv6KeyByItsSlash64() {
        assertThat(RateLimitKey.clientIp(InetAddress.ofLiteral("2001:db8:a:b:c:d:e:f")))
                .hasToString("IP 2001:db8:a:b::/64");
        assertThat(RateLimitKey.clientIp(InetAddress.ofLiteral("2001:db8::1")))
                .hasToString("IP 2001:db8:0:0::/64");
    }

    @Test
    void keysEachIpv4AddressOnItsOwn() {
        assertThat(RateLimitKey.clientIp(InetAddress.ofLiteral("203.0.113.7")))
                .hasToString("IP 203.0.113.7")
                .isNotEqualTo(RateLimitKey.clientIp(InetAddress.ofLiteral("203.0.113.8")));
    }

    @Test
    void keysAnIpv4AddressWrittenAsIpv6AsTheIpv4Address() {
        assertThat(RateLimitKey.clientIp(InetAddress.ofLiteral("::ffff:203.0.113.7")))
                .isEqualTo(RateLimitKey.clientIp(InetAddress.ofLiteral("203.0.113.7")))
                .isNotEqualTo(RateLimitKey.clientIp(InetAddress.ofLiteral("::ffff:203.0.113.8")));
    }

    /** The log names the key, so it holds the Account id, never an email. */
    @Test
    void namesAStudentByTheirAccountIdAndTheGlobalKeyEveryone() {
        assertThat(RateLimitKey.student(42)).hasToString("Student 42");
        assertThat(RateLimitKey.everyone()).hasToString("everyone");
    }

    private static InetAddress address(List<Integer> network, List<Integer> interfaceId) {
        return InetAddress.ofLiteral(Stream.concat(network.stream(), interfaceId.stream())
                .map(Integer::toHexString)
                .collect(Collectors.joining(":")));
    }

    private record SameNetwork(List<Integer> network, List<Integer> oneInterface, List<Integer> otherInterface) {
    }

    private record TwoNetworks(List<Integer> one, List<Integer> other, List<Integer> interfaceId) {
    }
}
