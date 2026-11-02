package br.com.readowl.readowl.media.application;

import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.text.ExternalUrls;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Imagens hospedadas em outros sites só valem nos domínios permitidos (readowl.images.allowed-hosts). A comparação é exata:
 * um subdomínio, como i.imgur.com.exemplo.com, não herda a permissão.
 */
@Component
public class ExternalImages {

    private final Set<String> allowedHosts;

    ExternalImages(ReadowlProperties properties) {
        this.allowedHosts = properties.images().allowedHosts().stream()
                .map(host -> host.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAllowed(String url) {
        return ExternalUrls.httpsHost(url).filter(allowedHosts::contains).isPresent();
    }

    public List<String> allowedHosts() {
        return allowedHosts.stream().sorted().toList();
    }
}
