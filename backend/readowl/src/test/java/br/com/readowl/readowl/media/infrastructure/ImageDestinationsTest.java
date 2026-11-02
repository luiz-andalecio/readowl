package br.com.readowl.readowl.media.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageDestinationsTest {

    private static final String ALLOWED = "https://i.imgur.com/a.jpg";

    private static ImageDestinations resolvingTo(String... addresses) {
        return new ImageDestinations(url -> url.startsWith("https://i.imgur.com"), host -> {
            InetAddress[] resolved = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                resolved[i] = InetAddress.getByName(addresses[i]);
            }
            return resolved;
        });
    }

    @Test
    void anAllowedSiteOnAPublicAddressPasses() {
        assertThatCode(() -> resolvingTo("151.101.1.1").requireSafe(URI.create(ALLOWED))).doesNotThrowAnyException();
        assertThatCode(() -> resolvingTo("2606:4700::1").requireSafe(URI.create(ALLOWED))).doesNotThrowAnyException();
        assertThatCode(() -> resolvingTo("151.101.1.1").requireSafe(URI.create("https://i.imgur.com:443/a.jpg"))).doesNotThrowAnyException();
    }

    @Test
    void aSiteOutsideTheListIsRefusedWithoutLookingItUp() {
        ImageDestinations destinations = new ImageDestinations(url -> false, host -> {
            throw new AssertionError("Não deveria consultar o DNS");
        });

        assertThatThrownBy(() -> destinations.requireSafe(URI.create("https://interno.example.com/a.jpg"))).isInstanceOf(IOException.class);
    }

    @Test
    void anotherPortIsRefused() {
        assertThatThrownBy(() -> resolvingTo("151.101.1.1").requireSafe(URI.create("https://i.imgur.com:8443/a.jpg"))).isInstanceOf(IOException.class);
    }

    @Test
    void aNameThatDoesNotResolveIsRefused() {
        ImageDestinations destinations = new ImageDestinations(url -> true, host -> {
            throw new UnknownHostException(host);
        });

        assertThatThrownBy(() -> destinations.requireSafe(URI.create(ALLOWED))).isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.8.8.8", "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254",
            "0.0.0.0", "0.1.2.3", "100.64.0.1", "100.127.255.255", "192.0.0.1", "198.18.0.1", "198.19.255.255", "224.0.0.1", "240.0.0.1",
            "255.255.255.255", "::", "::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "fe80::1", "fc00::1", "fd12:3456::1",
            "fec0::1", "ff02::1", "64:ff9b::a00:1", "2002:a00:1::"})
    void aNameThatPointsInsideTheNetworkIsRefused(String address) {
        assertThatThrownBy(() -> resolvingTo(address).requireSafe(URI.create(ALLOWED))).isInstanceOf(IOException.class);
    }

    @Test
    void oneInternalAddressAmongPublicOnesIsEnoughToRefuse() {
        assertThatThrownBy(() -> resolvingTo("151.101.1.1", "10.0.0.5").requireSafe(URI.create(ALLOWED))).isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "100.63.255.255", "100.128.0.1", "172.15.255.255", "172.32.0.1", "192.0.1.1", "198.17.0.1", "198.20.0.1",
            "2606:4700::1", "2a00:1450::1"})
    void theNeighborsOfTheInternalRangesArePublic(String address) throws UnknownHostException {
        assertThat(ImageDestinations.isPublic(InetAddress.getByName(address))).isTrue();
    }
}
