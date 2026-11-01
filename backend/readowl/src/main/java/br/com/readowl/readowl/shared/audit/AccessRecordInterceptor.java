package br.com.readowl.readowl.shared.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.UUID;

/**
 * Registra as escritas bem-sucedidas de quem está logado, o que liga um comentário ou uma publicação a uma conexão.
 * As rotas de autenticação usam {@link SecurityEvents}. O salvamento automático fica de fora: é frequente e não
 * publica nada.
 */
@Component
class AccessRecordInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String AUTH_PATH = "/api/v1/auth/";
    private static final String DRAFT_SUFFIX = "/draft";

    private final AccessRecords records;

    AccessRecordInterceptor(AccessRecords records) {
        this.records = records;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        String path = request.getServletPath();
        if (ex != null || response.getStatus() >= HttpStatus.BAD_REQUEST.value()
                || SAFE_METHODS.contains(request.getMethod())
                || path.startsWith(AUTH_PATH) || path.endsWith(DRAFT_SUFFIX)) {
            return;
        }
        UUID actorId = AuditTrail.currentActorId();
        if (actorId != null) {
            records.record(actorId, request.getMethod() + " " + path);
        }
    }
}
