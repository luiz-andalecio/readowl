package br.com.readowl.readowl.shared.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Set;
import java.util.UUID;

/**
 * Registra alterações nas rotas administrativas e as feitas por administradores em conteúdo de outras pessoas,
 * com o resultado, inclusive quando falham. Roda depois da resposta, fora da transação da ação. Por isso as
 * ações sensíveis também usam {@link AuditTrail#record}, que não depende disso.
 */
@Component
class AuditInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final AuditTrail trail;

    AuditInterceptor(AuditTrail trail) {
        this.trail = trail;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        String path = request.getRequestURI();
        if (SAFE_METHODS.contains(request.getMethod())
                || !(AuditTrail.isAdministrativePath(path) || AuditContext.isOverride())) {
            return;
        }
        AuditOutcome outcome = outcome(response.getStatus(), ex);
        if (outcome == AuditOutcome.SUCCESS && AuditContext.isRecorded()) {
            return;
        }
        UUID actorId = AuditTrail.currentActorId();
        if (actorId != null) {
            String action = request.getMethod() + " " + request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            trail.recordRequest(actorId, action, path, outcome);
        }
    }

    private static AuditOutcome outcome(int status, Exception ex) {
        if (ex != null) {
            return AuditOutcome.FAILED;
        }
        if (status == HttpStatus.FORBIDDEN.value()) {
            return AuditOutcome.DENIED;
        }
        return status < HttpStatus.BAD_REQUEST.value() ? AuditOutcome.SUCCESS : AuditOutcome.FAILED;
    }
}
