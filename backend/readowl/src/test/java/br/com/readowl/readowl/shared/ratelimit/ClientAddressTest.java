package br.com.readowl.readowl.shared.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressTest {

    @Test
    void ipv4IsKeptAsIs() {
        assertThat(ClientAddress.normalize("198.51.100.7")).isEqualTo("198.51.100.7");
    }

    @Test
    void addressesOfTheSameIpv6NetworkAreTheSameClient() {
        assertThat(ClientAddress.normalize("2001:db8:1:2:aaaa::1"))
                .isEqualTo(ClientAddress.normalize("2001:db8:1:2:bbbb:cccc:dddd:eeee"))
                .isEqualTo("20010db800010002::/64");
    }

    @Test
    void differentIpv6NetworksAreDifferentClients() {
        assertThat(ClientAddress.normalize("2001:db8:1:2::1")).isNotEqualTo(ClientAddress.normalize("2001:db8:1:3::1"));
    }

    @Test
    void ipv4WrittenAsIpv6IsTheSameClientAsIpv4() {
        assertThat(ClientAddress.normalize("::ffff:198.51.100.7")).isEqualTo("198.51.100.7");
    }

    @Test
    void anythingElseIsNotResolvedByDns() {
        assertThat(ClientAddress.normalize("readowl.example.com")).isEqualTo("readowl.example.com");
    }
}
