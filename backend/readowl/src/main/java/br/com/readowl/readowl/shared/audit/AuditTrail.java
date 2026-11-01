package br.com.readowl.readowl.shared.audit;

import br.com.readowl.readowl.shared.logging.CorrelationIdFilter;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Registro das ações administrativas. Nunca recebe corpos de requisição, que podem ter texto privado de capítulos.
 */
@Service
public class AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(AuditTrail.class);
    private static final String ADMIN_PATH = "/api/v1/admin/";
    private static final String MODERATION_PATH = "/api/v1/moderation/";
    private static final int MAX_ACTION_LENGTH = 200;
    private static final int MAX_RESOURCE_LENGTH = 500;

    private final AuditEntryRepository entries;
    private final JsonMapper jsonMapper;
    private final RateLimiter rateLimiter;

    AuditTrail(AuditEntryRepository entries, JsonMapper jsonMapper, RateLimiter rateLimiter) {
        this.entries = entries;
        this.jsonMapper = jsonMapper;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Para ações sensíveis: grava na mesma transação da ação, então uma não existe sem a outra.
     *
     * @param details mudanças e motivos, sem segredos
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorId, String action, String resource, Map<String, ?> details) {
        entries.save(new AuditEntry(actorId, action, resource, AuditOutcome.SUCCESS, correlationId(),
                jsonMapper.writeValueAsString(details)));
        AuditContext.markRecorded();
    }

    void recordRequest(UUID actorId, String action, String resource, AuditOutcome outcome) {
        // O caminho vem do cliente e pode ser maior que as colunas.
        String storedResource = truncate(resource, MAX_RESOURCE_LENGTH);
        try {
            entries.save(new AuditEntry(actorId, truncate(action, MAX_ACTION_LENGTH), storedResource, outcome,
                    correlationId(), null));
        } catch (RuntimeException failure) {
            // A resposta já foi definida: a falha não pode passar despercebida, mas não muda o resultado da ação.
            log.error("Falha ao gravar a auditoria: rota={}", storedResource, failure);
        }
    }

    /**
     * Recusa do filtro de segurança, que acontece antes de {@link AuditInterceptor}. Só vale para rotas
     * administrativas e para quem está logado: de visitantes, não há quem registrar. Tem um limite por conta,
     * para que ninguém encha a auditoria só repetindo pedidos recusados.
     */
    public void recordDenied(String method, String path) {
        UUID actorId = currentActorId();
        if (actorId == null || !isAdministrativePath(path)) {
            return;
        }
        try {
            if (rateLimiter.tryAcquire(RateLimitPolicy.AUDIT_DENIED_ACCOUNT, actorId.toString())) {
                recordRequest(actorId, method + " " + path, path, AuditOutcome.DENIED);
            }
        } catch (RuntimeException failure) {
            log.error("Falha ao verificar o limite da auditoria", failure);
        }
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    static boolean isAdministrativePath(String path) {
        return path.startsWith(ADMIN_PATH) || path.startsWith(MODERATION_PATH);
    }

    static UUID currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal
                ? UUID.fromString(principal.getName())
                : null;
    }

    private static String correlationId() {
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }
}
