package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.shared.exception.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Recusa as senhas que primeiro entram em um ataque, apesar do tamanho aceitável. A lista é local: nenhuma senha sai do
 * servidor. Letras repetidas ou uma sequência de um único caractere também não passam.
 */
@Component
public class PasswordPolicy {

    private final Set<String> commonPasswords;

    PasswordPolicy() {
        try {
            String list = new ClassPathResource("common-passwords.txt").getContentAsString(StandardCharsets.UTF_8);
            this.commonPasswords = list.lines().map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public void requireAcceptable(String password) {
        String normalized = password.toLowerCase(Locale.ROOT);
        if (commonPasswords.contains(normalized) || normalized.chars().distinct().count() < 3) {
            throw ApiException.badRequest("PASSWORD_TOO_COMMON", "Esta senha é muito comum. Escolha outra.");
        }
    }
}
