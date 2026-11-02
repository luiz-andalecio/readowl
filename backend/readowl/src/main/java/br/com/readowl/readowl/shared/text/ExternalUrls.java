package br.com.readowl.readowl.shared.text;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Endereços de outros sites informados por usuários. Esta classe só confere o formato. Quase todos são abertos só pelo
 * navegador de quem lê. O único acesso pelo servidor é o download de capas e banners, com as proteções de HttpRemoteImages.
 */
public final class ExternalUrls {

    private ExternalUrls() {
    }

    /**
     * @return o domínio, em minúsculas, de um endereço https completo. Vazio em todo o resto, inclusive com usuário e senha
     * no endereço, que servem para disfarçar o destino, e com um IP em vez de um domínio.
     */
    public static Optional<String> httpsHost(String url) {
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null) {
                return Optional.empty();
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            boolean isName = normalized.contains(".") && !normalized.startsWith("[") && !normalized.matches("[0-9.]+");
            return isName ? Optional.of(normalized) : Optional.empty();
        } catch (URISyntaxException ex) {
            return Optional.empty();
        }
    }

    public static boolean isHttps(String url) {
        return httpsHost(url).isPresent();
    }
}
