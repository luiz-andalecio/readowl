package br.com.readowl.readowl.user.domain;

import br.com.readowl.readowl.shared.text.ExternalUrls;

import java.util.List;

/**
 * Onde encontrar o autor fora do Readowl. O domínio é conferido, para que um link que diz ser do Apoia-se não leve a outro
 * site. WEBSITE aceita qualquer domínio.
 */
public enum ExternalLinkType {
    DISCORD("discord.gg", "discord.com"),
    APOIA_SE("apoia.se"),
    PATREON("patreon.com"),
    KO_FI("ko-fi.com"),
    INSTAGRAM("instagram.com"),
    X("x.com", "twitter.com"),
    YOUTUBE("youtube.com", "youtu.be"),
    TIKTOK("tiktok.com"),
    FACEBOOK("facebook.com"),
    BLUESKY("bsky.app"),
    TWITCH("twitch.tv"),
    WEBSITE;

    private final List<String> domains;

    ExternalLinkType(String... domains) {
        this.domains = List.of(domains);
    }

    public boolean accepts(String url) {
        return ExternalUrls.httpsHost(url)
                .filter(host -> domains.isEmpty() || domains.stream()
                        .anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain)))
                .isPresent();
    }
}
