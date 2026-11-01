package br.com.readowl.readowl.shared.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Único ponto que decide quem é o cliente para os limites por IP. Uma conexão IPv6 costuma controlar um bloco /64
 * inteiro, então o bloco conta como um único cliente.
 */
public final class ClientAddress {

    private static final int IPV6_NETWORK_BYTES = 8;

    private ClientAddress() {
    }

    public static String of(HttpServletRequest request) {
        return normalize(request.getRemoteAddr());
    }

    static String normalize(String address) {
        try {
            // ofLiteral nunca consulta o DNS, ao contrário de getByName.
            InetAddress parsed = InetAddress.ofLiteral(address);
            if (parsed instanceof Inet6Address) {
                return HexFormat.of().formatHex(Arrays.copyOf(parsed.getAddress(), IPV6_NETWORK_BYTES)) + "::/64";
            }
            return parsed.getHostAddress();
        } catch (IllegalArgumentException ex) {
            return address;
        }
    }
}
