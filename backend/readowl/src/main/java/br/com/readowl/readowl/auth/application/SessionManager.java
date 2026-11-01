package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.user.application.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
public class SessionManager {

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfTokens;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final Duration sessionTimeout;

    SessionManager(SecurityContextRepository contextRepository, CsrfTokenRepository csrfTokens,
                   FindByIndexNameSessionRepository<? extends Session> sessions,
                   @Value("${spring.session.timeout}") Duration sessionTimeout) {
        this.contextRepository = contextRepository;
        this.csrfTokens = csrfTokens;
        this.sessions = sessions;
        this.sessionTimeout = sessionTimeout;
    }

    /**
     * Autentica a sessão com um novo ID, contra fixação de sessão. O token CSRF é descartado: o cliente
     * obtém um novo em GET /auth/csrf. A sessão criada para o login com Google tinha validade curta e passa
     * a ter a normal.
     */
    public void establish(SessionUser user, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities()));
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        request.getSession().setMaxInactiveInterval((int) sessionTimeout.toSeconds());
        csrfTokens.saveToken(null, request, response);
    }

    /**
     * Encerra a sessão atual, removendo qualquer autenticação que ela guarde.
     */
    public void terminate(HttpServletRequest request, HttpServletResponse response) {
        contextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        csrfTokens.saveToken(null, request, response);
    }

    public void revokeAll(UUID userId) {
        sessions.findByPrincipalName(userId.toString()).keySet().forEach(sessions::deleteById);
    }

    /**
     * Precisa ser chamado antes de trocar o ID da sessão atual, que ainda está gravada com o ID antigo.
     */
    public void revokeAllExceptCurrent(UUID userId, HttpServletRequest request) {
        HttpSession current = request.getSession(false);
        sessions.findByPrincipalName(userId.toString()).keySet().stream()
                .filter(id -> current == null || !id.equals(current.getId()))
                .forEach(sessions::deleteById);
    }
}
