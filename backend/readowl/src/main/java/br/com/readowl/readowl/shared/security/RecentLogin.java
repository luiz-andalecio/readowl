package br.com.readowl.readowl.shared.security;

import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.exception.ApiException;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;

/**
 * Ações sensíveis exigem ter entrado há pouco: um cookie roubado, ou um computador deixado aberto, não bastam.
 */
@Component
public class RecentLogin {

    public static final String AUTHENTICATED_AT = "readowl.authenticatedAt";

    private final ReadowlProperties properties;

    RecentLogin(ReadowlProperties properties) {
        this.properties = properties;
    }

    public void require() {
        if (!isRecent()) {
            throw ApiException.forbidden("RECENT_LOGIN_REQUIRED", "Entre novamente para continuar.");
        }
    }

    private boolean isRecent() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return false;
        }
        HttpSession session = attributes.getRequest().getSession(false);
        return session != null && session.getAttribute(AUTHENTICATED_AT) instanceof Long at
                && Instant.ofEpochSecond(at).plus(properties.sessions().recentLogin()).isAfter(Instant.now());
    }
}
