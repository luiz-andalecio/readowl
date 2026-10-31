package br.com.readowl.readowl.shared.audit;

import br.com.readowl.readowl.shared.logging.CorrelationIdFilter;
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

    private final AuditEntryRepository entries;
    private final JsonMapper jsonMapper;

    AuditTrail(AuditEntryRepository entries, JsonMapper jsonMapper) {
        this.entries = entries;
        this.jsonMapper = jsonMapper;
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
        try {
            entries.save(new AuditEntry(actorId, action, resource, outcome, correlationId(), null));
        } catch (RuntimeException failure) {
            // A resposta já foi definida: a falha não pode passar despercebida, mas não muda o resultado da ação.
            log.error("Falha ao gravar a auditoria: rota={}", resource, failure);
        }
    }

    /**
     * Recusa do filtro de segurança, que acontece antes de {@link AuditInterceptor}. Só vale para rotas
     * administrativas e para quem está logado: de visitantes, não há quem registrar.
     */
    public void recordDenied(String method, String path) {
        UUID actorId = currentActorId();
        if (actorId != null && isAdministrativePath(path)) {
            recordRequest(actorId, method + " " + path, path, AuditOutcome.DENIED);
        }
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
