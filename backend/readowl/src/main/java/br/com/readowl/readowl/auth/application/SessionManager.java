package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.shared.audit.SecurityEvents;
import br.com.readowl.readowl.shared.config.ReadowlProperties;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.security.Hashing;
import br.com.readowl.readowl.shared.security.RecentLogin;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.domain.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.web.authentication.SpringSessionRememberMeServices;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class SessionManager {

    private static final String REMEMBERED = "readowl.remembered";
    private static final String USER_AGENT = "readowl.userAgent";
    private static final int MAX_USER_AGENT_LENGTH = 200;
    private static final int PUBLIC_ID_LENGTH = 32;

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfTokens;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final Duration idle;
    private final ReadowlProperties.Sessions limits;
    private final SecurityEvents events;

    SessionManager(SecurityContextRepository contextRepository, CsrfTokenRepository csrfTokens,
                   FindByIndexNameSessionRepository<? extends Session> sessions,
                   @Value("${spring.session.timeout}") Duration idle, ReadowlProperties properties,
                   SecurityEvents events) {
        this.contextRepository = contextRepository;
        this.csrfTokens = csrfTokens;
        this.sessions = sessions;
        this.idle = idle;
        this.limits = properties.sessions();
        this.events = events;
    }

    /**
     * O identificador é um hash: o ID da sessão é o valor do cookie e nunca sai em uma resposta.
     */
    public record SessionView(String id, Instant createdAt, Instant lastAccessedAt, String userAgent, boolean current) {
    }

    /**
     * Mantém a escolha de "manter conectado" que a sessão já tinha, como ao trocar a senha.
     */
    public void establish(SessionUser user, HttpServletRequest request, HttpServletResponse response) {
        HttpSession existing = request.getSession(false);
        establish(user, request, response, existing != null && Boolean.TRUE.equals(existing.getAttribute(REMEMBERED)));
    }

    /**
     * Autentica a sessão com um novo ID, contra fixação de sessão. O token CSRF é descartado: o cliente
     * obtém um novo em GET /auth/csrf. A sessão criada para o login com Google tinha validade curta e passa
     * a ter a normal. Contas com papel de moderação ou administração têm prazos menores e nunca ficam conectadas.
     */
    public void establish(SessionUser user, HttpServletRequest request, HttpServletResponse response, boolean remember) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities()));
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        boolean privileged = user.role() != Role.USER;
        boolean remembered = remember && !privileged;
        HttpSession session = request.getSession();
        session.setMaxInactiveInterval((int) idleFor(privileged, remembered).toSeconds());
        session.setAttribute(RecentLogin.AUTHENTICATED_AT, Instant.now().getEpochSecond());
        session.setAttribute(REMEMBERED, remembered);
        session.setAttribute(USER_AGENT, userAgent(request));
        // Sem isto, o cookie some ao fechar o navegador. Com isto, não tem prazo próprio: quem decide é a sessão no servidor.
        if (remembered) {
            request.setAttribute(SpringSessionRememberMeServices.REMEMBER_ME_LOGIN_ATTR, Boolean.TRUE);
        }
        csrfTokens.saveToken(null, request, response);
    }

    private Duration idleFor(boolean privileged, boolean remembered) {
        if (privileged) {
            return limits.privilegedIdle();
        }
        return remembered ? limits.rememberedIdle() : idle;
    }

    private static String userAgent(HttpServletRequest request) {
        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        if (agent == null) {
            return "";
        }
        return agent.length() <= MAX_USER_AGENT_LENGTH ? agent : agent.substring(0, MAX_USER_AGENT_LENGTH);
    }

    boolean isWithinMaxAge(SessionUser user, HttpSession session) {
        Duration maxAge = user.role() != Role.USER ? limits.privilegedMaxAge() : limits.maxAge();
        return Instant.ofEpochMilli(session.getCreationTime()).plus(maxAge).isAfter(Instant.now());
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

    public List<SessionView> list(SessionUser user, HttpServletRequest request) {
        HttpSession current = request.getSession(false);
        Duration maxAge = user.role() != Role.USER ? limits.privilegedMaxAge() : limits.maxAge();
        Instant now = Instant.now();
        return sessions.findByPrincipalName(user.getName()).entrySet().stream()
                .filter(entry -> entry.getValue().getCreationTime().plus(maxAge).isAfter(now))
                .map(entry -> view(entry, current))
                .sorted(Comparator.comparing(SessionView::lastAccessedAt).reversed())
                .toList();
    }

    private static SessionView view(Map.Entry<String, ? extends Session> entry, HttpSession current) {
        Session session = entry.getValue();
        return new SessionView(publicId(entry.getKey()), session.getCreationTime(), session.getLastAccessedTime(),
                session.getAttribute(USER_AGENT), current != null && entry.getKey().equals(current.getId()));
    }

    /**
     * Encerrar a sessão atual equivale a sair.
     */
    public void revoke(SessionUser user, String publicId, HttpServletRequest request, HttpServletResponse response) {
        String sessionId = sessions.findByPrincipalName(user.getName()).keySet().stream()
                .filter(id -> publicId(id).equals(publicId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("SESSION_NOT_FOUND", "Sessão não encontrada."));
        HttpSession current = request.getSession(false);
        if (current != null && current.getId().equals(sessionId)) {
            terminate(request, response);
        } else {
            sessions.deleteById(sessionId);
        }
        events.record(SecurityEvents.Event.SESSION_REVOKED, user.id());
    }

    public void revokeOthers(SessionUser user, HttpServletRequest request) {
        revokeAllExceptCurrent(user.id(), request);
        events.record(SecurityEvents.Event.SESSION_REVOKED, user.id());
    }

    private static String publicId(String sessionId) {
        return Hashing.sha256Hex(sessionId).substring(0, PUBLIC_ID_LENGTH);
    }
}
