package br.com.readowl.readowl.auth.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Marca, na sessão, que o próximo retorno do Google deve vincular a conta em vez de fazer login.
 * <p>
 * É serializado no banco: renomear ou mover esta classe invalida as sessões existentes.
 */
public record GoogleLinkIntent(UUID userId, Instant expiresAt) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String ATTRIBUTE = "readowl.googleLinkIntent";
    private static final Duration VALIDITY = Duration.ofMinutes(10);

    public static void store(UUID userId, HttpServletRequest request) {
        request.getSession().setAttribute(ATTRIBUTE, new GoogleLinkIntent(userId, Instant.now().plus(VALIDITY)));
    }

    /**
     * Devolve o usuário da intenção ainda válida, removendo-a da sessão: cada intenção vale uma vez.
     */
    public static Optional<UUID> take(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(ATTRIBUTE) instanceof GoogleLinkIntent intent)) {
            return Optional.empty();
        }
        session.removeAttribute(ATTRIBUTE);
        return Instant.now().isBefore(intent.expiresAt()) ? Optional.of(intent.userId()) : Optional.empty();
    }
}
