package br.com.readowl.readowl.moderation.domain;

import br.com.readowl.readowl.shared.domain.BaseEntity;
import br.com.readowl.readowl.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A cópia do conteúdo preserva a prova: o alvo pode ser editado ou excluído depois da denúncia.
 */
@Entity
@Table(name = "reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportTargetType targetType;

    @Column(nullable = false)
    private UUID targetId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "target_owner_id", nullable = false)
    private User targetOwner;

    @Column(nullable = false)
    private String targetSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReportReason reason;

    @Column(length = 1000)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status = ReportStatus.OPEN;

    @Column(length = 1000)
    private String resolutionNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_id")
    private User resolvedBy;

    private Instant resolvedAt;

    public Report(User reporter, ReportTargetType targetType, UUID targetId, User targetOwner, String targetSnapshot,
                  ReportReason reason, String details) {
        this.reporter = reporter;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetOwner = targetOwner;
        this.targetSnapshot = targetSnapshot;
        this.reason = reason;
        this.details = details;
    }

    public boolean isOpen() {
        return status == ReportStatus.OPEN;
    }

    public void close(ReportStatus status, String note, User resolver, Instant at) {
        this.status = status;
        this.resolutionNote = note;
        this.resolvedBy = resolver;
        this.resolvedAt = at;
    }
}
