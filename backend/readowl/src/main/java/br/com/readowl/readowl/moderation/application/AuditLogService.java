package br.com.readowl.readowl.moderation.application;

import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.audit.AuditEntry;
import br.com.readowl.readowl.shared.audit.AuditEntryRepository;
import br.com.readowl.readowl.shared.audit.AuditOutcome;
import br.com.readowl.readowl.user.application.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Só leitura: nenhuma rota altera ou exclui entradas da auditoria.
 */
@Service
@Transactional(readOnly = true)
public class AuditLogService {

    private final AuditEntryRepository entries;
    private final UserService users;
    private final JsonMapper jsonMapper;

    AuditLogService(AuditEntryRepository entries, UserService users, JsonMapper jsonMapper) {
        this.entries = entries;
        this.users = users;
        this.jsonMapper = jsonMapper;
    }

    public record AuditLogEntry(UUID id, UUID actorId, String actorUsername, String action, String resource,
                                AuditOutcome outcome, String correlationId, JsonNode details, Instant createdAt) {
    }

    /**
     * @param actorId nulo para todos
     * @param resource nulo para todos; o valor exato registrado, como users/019f...
     */
    public PageResponse<AuditLogEntry> list(UUID actorId, String resource, Pageable pageable) {
        Specification<AuditEntry> filter = Specification.unrestricted();
        if (actorId != null) {
            filter = filter.and((root, query, builder) -> builder.equal(root.get("actorId"), actorId));
        }
        if (resource != null && !resource.isBlank()) {
            filter = filter.and((root, query, builder) -> builder.equal(root.get("resource"), resource.trim()));
        }
        Page<AuditEntry> page = entries.findAll(filter, pageable);
        Set<UUID> actors = page.getContent().stream().map(AuditEntry::getActorId).collect(Collectors.toSet());
        Map<UUID, String> usernames = users.usernamesById(actors);
        return PageResponse.from(page.map(entry -> new AuditLogEntry(entry.getId(), entry.getActorId(),
                usernames.get(entry.getActorId()), entry.getAction(), entry.getResource(), entry.getOutcome(),
                entry.getCorrelationId(), entry.getDetails() == null ? null : jsonMapper.readTree(entry.getDetails()),
                entry.getCreatedAt())));
    }
}
