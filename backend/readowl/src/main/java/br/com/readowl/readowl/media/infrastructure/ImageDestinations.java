package br.com.readowl.readowl.media.infrastructure;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.function.Predicate;

/**
 * O endereço de uma imagem vem de quem usa o site, então o servidor não pode ser levado à própria rede interna (SSRF). Só
 * aceita https, na porta 443, em domínios da lista, e o domínio não pode apontar para um endereço interno. Se o DNS mudar
 * depois desta conferência, o certificado do site permitido deixa de valer, e a conexão falha.
 */
class ImageDestinations {

    @FunctionalInterface
    interface AddressLookup {
        InetAddress[] addressesOf(String host) throws UnknownHostException;
    }

    private final Predicate<String> allowed;
    private final AddressLookup lookup;

    ImageDestinations(Predicate<String> allowed, AddressLookup lookup) {
        this.allowed = allowed;
        this.lookup = lookup;
    }

    void requireSafe(URI uri) throws IOException {
        if (!allowed.test(uri.toString()) || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IOException("Destino fora dos sites permitidos");
        }
        for (InetAddress address : lookup.addressesOf(uri.getHost())) {
            if (!isPublic(address)) {
                throw new IOException("Destino em um endereço interno");
            }
        }
    }

    /**
     * Fora as faixas que o Java já identifica: IPv4 reservado, compartilhado (100.64/10) e de testes (198.18/15), e IPv6 local
     * (fc00::/7), reservado (::/8, que inclui o NAT64) e de túnel para IPv4 (2002::/16).
     */
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] ip = address.getAddress();
        int first = ip[0] & 0xFF;
        int second = ip[1] & 0xFF;
        if (ip.length == 4) {
            return first != 0 && first < 240
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 192 && second == 0 && ip[2] == 0)
                    && !(first == 198 && (second == 18 || second == 19));
        }
        return first != 0 && (first & 0xFE) != 0xFC && !(first == 0x20 && second == 0x02);
    }
}
