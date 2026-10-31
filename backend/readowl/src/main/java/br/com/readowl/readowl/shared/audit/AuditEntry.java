package br.com.readowl.readowl.shared.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Nunca é alterada nem excluída pela aplicação. O ator é só o identificador porque shared não conhece o módulo
 * de usuários; a chave estrangeira fica no banco.
 */
@Entity
@Immutable
@Table(name = "audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEntry {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false)
    private UUID actorId;

    @Column(nullable = false, length = 200)
    private String action;

    @Column(nullable = false, length = 500)
    private String resource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuditOutcome outcome;

    @Column(length = 36)
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    private String details;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    AuditEntry(UUID actorId, String action, String resource, AuditOutcome outcome, String correlationId, String details) {
        this.actorId = actorId;
        this.action = action;
        this.resource = resource;
        this.outcome = outcome;
        this.correlationId = correlationId;
        this.details = details;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
