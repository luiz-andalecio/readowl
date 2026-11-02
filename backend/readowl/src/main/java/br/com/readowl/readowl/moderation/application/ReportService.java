package br.com.readowl.readowl.moderation.application;

import br.com.readowl.readowl.book.application.PublicBooks;
import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.community.application.CommentModeration;
import br.com.readowl.readowl.community.domain.Comment;
import br.com.readowl.readowl.moderation.domain.Report;
import br.com.readowl.readowl.moderation.domain.ReportReason;
import br.com.readowl.readowl.moderation.domain.ReportStatus;
import br.com.readowl.readowl.moderation.domain.ReportTargetType;
import br.com.readowl.readowl.moderation.infrastructure.ReportRepository;
import br.com.readowl.readowl.notification.application.NotificationService;
import br.com.readowl.readowl.notification.domain.NotificationType;
import br.com.readowl.readowl.publishing.application.PublicChapters;
import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.publishing.domain.ChapterRevision;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.audit.AuditTrail;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimiter;
import br.com.readowl.readowl.shared.text.Texts;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import br.com.readowl.readowl.user.domain.User;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Só o que está público pode ser denunciado: quem denuncia não descobre se existe algo privado com aquele
 * identificador. Quem denunciou só aparece para a moderação.
 */
@Service
public class ReportService {

    // A cópia do capítulo preserva a prova mesmo se o autor o excluir antes da análise.
    private static final int MAX_CHAPTER_SNAPSHOT_LENGTH = 50_000;

    private final ReportRepository reports;
    private final PublicBooks publicBooks;
    private final PublicChapters publicChapters;
    private final CommentModeration comments;
    private final UserService users;
    private final AuditTrail audit;
    private final RateLimiter rateLimiter;
    private final TransactionOperations transaction;
    private final NotificationService notifications;

    ReportService(ReportRepository reports, PublicBooks publicBooks, PublicChapters publicChapters,
                  CommentModeration comments, UserService users, AuditTrail audit, RateLimiter rateLimiter,
                  TransactionOperations transaction, NotificationService notifications) {
        this.notifications = notifications;
        this.reports = reports;
        this.publicBooks = publicBooks;
        this.publicChapters = publicChapters;
        this.comments = comments;
        this.users = users;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.transaction = transaction;
    }

    public record NewReport(ReportTargetType targetType, UUID targetId, ReportReason reason, String details) {
    }

    public record ReportReceipt(UUID id, ReportTargetType targetType, UUID targetId, ReportReason reason,
                                ReportStatus status, Instant createdAt) {
    }

    public record ReportUser(UUID id, String username) {

        static ReportUser from(User user) {
            return user == null ? null : new ReportUser(user.getId(), user.getUsername());
        }
    }

    public record ReportView(UUID id, ReportTargetType targetType, UUID targetId, String targetSnapshot,
                             ReportUser targetOwner, ReportReason reason, String details, ReportUser reporter,
                             ReportStatus status, String resolutionNote, ReportUser resolvedBy, Instant resolvedAt,
                             Instant createdAt) {

        static ReportView from(Report report) {
            return new ReportView(report.getId(), report.getTargetType(), report.getTargetId(),
                    report.getTargetSnapshot(), ReportUser.from(report.getTargetOwner()), report.getReason(),
                    report.getDetails(), ReportUser.from(report.getReporter()), report.getStatus(),
                    report.getResolutionNote(), ReportUser.from(report.getResolvedBy()), report.getResolvedAt(),
                    report.getCreatedAt());
        }
    }

    private record Target(User owner, String snapshot) {
    }

    public ReportReceipt create(NewReport data, SessionUser principal, String clientIp) {
        rateLimiter.acquire(RateLimitPolicy.REPORT_IP, clientIp);
        rateLimiter.acquire(RateLimitPolicy.REPORT_ACCOUNT, principal.getName());
        try {
            return transaction.execute(status -> {
                Target target = resolveTarget(data.targetType(), data.targetId());
                if (target.owner().getId().equals(principal.id())) {
                    throw ApiException.forbidden("OWN_CONTENT_REPORT", "Você não pode denunciar o próprio conteúdo.");
                }
                if (hasOpenReport(data, principal)) {
                    throw alreadyOpen();
                }
                Report report = reports.saveAndFlush(new Report(users.requireById(principal.id()), data.targetType(),
                        data.targetId(), target.owner(), target.snapshot(), data.reason(), Texts.blankToNull(data.details())));
                return new ReportReceipt(report.getId(), report.getTargetType(), report.getTargetId(), report.getReason(),
                        report.getStatus(), report.getCreatedAt());
            });
        } catch (DataIntegrityViolationException ex) {
            // Dois envios simultâneos: consulta de novo porque a mensagem do banco varia com o idioma do servidor.
            if (hasOpenReport(data, principal)) {
                throw alreadyOpen();
            }
            throw ex;
        }
    }

    /**
     * @param status nulo para todos
     * @param targetType nulo para todos
     */
    @Transactional(readOnly = true)
    public PageResponse<ReportView> list(ReportStatus status, ReportTargetType targetType, Pageable pageable) {
        Specification<Report> filter = Specification.unrestricted();
        if (status != null) {
            filter = filter.and((root, query, builder) -> builder.equal(root.get("status"), status));
        }
        if (targetType != null) {
            filter = filter.and((root, query, builder) -> builder.equal(root.get("targetType"), targetType));
        }
        return PageResponse.from(reports.findAll(filter, pageable).map(ReportView::from));
    }

    @Transactional(readOnly = true)
    public ReportView get(UUID id) {
        return ReportView.from(require(id));
    }

    /**
     * O lock otimista impede que duas pessoas encerrem a mesma denúncia ao mesmo tempo.
     */
    @Transactional
    public ReportView close(UUID id, ReportStatus status, String note, SessionUser principal) {
        if (status == ReportStatus.OPEN) {
            throw ApiException.badRequest("INVALID_REPORT_STATUS", "Encerre a denúncia com RESOLVED ou DISMISSED.");
        }
        Report report = require(id);
        principal.requireIndependentReview(report.getTargetOwner().getId());
        if (!report.isOpen()) {
            throw ApiException.conflict("REPORT_CLOSED", "Esta denúncia já foi encerrada.");
        }
        report.close(status, Texts.blankToNull(note), users.requireById(principal.id()), Instant.now());
        reports.saveAndFlush(report);
        // Só o resultado: a nota é interna, e dizer o que foi feito com o alvo exporia outra pessoa.
        notifications.notifyModeration(report.getReporter().getId(), status == ReportStatus.RESOLVED
                ? NotificationType.REPORT_RESOLVED : NotificationType.REPORT_DISMISSED, null, null, null);
        audit.record(principal.id(), "REPORT_CLOSED", "reports/" + id, Map.of("status", status));
        return ReportView.from(report);
    }

    private Target resolveTarget(ReportTargetType type, UUID id) {
        return switch (type) {
            case BOOK -> {
                Book book = publicBooks.requireById(id);
                String cover = book.getCoverUrl() == null ? "" : "\n\nCapa: " + book.getCoverUrl();
                String banner = book.getBannerUrl() == null ? "" : "\n\nBanner: " + book.getBannerUrl();
                yield new Target(book.getAuthor(), book.getTitle() + "\n\n" + book.getSynopsis() + cover + banner);
            }
            case CHAPTER -> {
                Chapter chapter = publicChapters.requirePublic(id);
                ChapterRevision revision = chapter.getPublishedRevision();
                String text = revision.getPlainText();
                yield new Target(chapter.getBook().getAuthor(),
                        chapter.getTitle() + " (revisão " + revision.getRevisionNumber() + ")\n\n"
                                + text.substring(0, Math.min(text.length(), MAX_CHAPTER_SNAPSHOT_LENGTH)));
            }
            case COMMENT -> {
                Comment comment = comments.requireVisible(id);
                String image = comment.getImageUrl() == null ? "" : "\n\nImagem: " + comment.getImageUrl();
                yield new Target(comment.getUser(), comment.getContent() + image);
            }
            case USER -> {
                User user = users.findById(id).filter(candidate -> candidate.isActive() && candidate.isEmailVerified())
                        .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "Usuário não encontrado."));
                String description = user.getDescription() == null ? "" : "\n\n" + user.getDescription();
                String avatar = user.getAvatarUrl() == null ? "" : "\n\nFoto: " + user.getAvatarUrl();
                String links = users.linksOf(user.getId()).stream().map(link -> "\n" + link.type() + ": " + link.url())
                        .collect(Collectors.joining());
                yield new Target(user, user.getUsername() + " (" + user.getName() + ")" + description + avatar + links);
            }
        };
    }

    private boolean hasOpenReport(NewReport data, SessionUser principal) {
        return reports.existsByReporterIdAndTargetTypeAndTargetIdAndStatus(principal.id(), data.targetType(),
                data.targetId(), ReportStatus.OPEN);
    }

    private Report require(UUID id) {
        return reports.findById(id)
                .orElseThrow(() -> ApiException.notFound("REPORT_NOT_FOUND", "Denúncia não encontrada."));
    }

    private static ApiException alreadyOpen() {
        return ApiException.conflict("REPORT_ALREADY_OPEN", "Você já tem uma denúncia em aberto sobre este conteúdo.");
    }
}
