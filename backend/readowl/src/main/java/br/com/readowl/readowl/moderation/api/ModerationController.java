package br.com.readowl.readowl.moderation.api;

import br.com.readowl.readowl.community.application.CommentModeration.ModeratedComment;
import br.com.readowl.readowl.moderation.application.AuditLogService;
import br.com.readowl.readowl.moderation.application.ModerationService;
import br.com.readowl.readowl.moderation.application.ReportService;
import br.com.readowl.readowl.moderation.application.ReportService.ReportReceipt;
import br.com.readowl.readowl.moderation.application.ReportService.ReportView;
import br.com.readowl.readowl.moderation.domain.ReportReason;
import br.com.readowl.readowl.moderation.domain.ReportStatus;
import br.com.readowl.readowl.moderation.domain.ReportTargetType;
import br.com.readowl.readowl.shared.api.PageRequests;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.ratelimit.ClientAddress;
import br.com.readowl.readowl.user.application.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class ModerationController {

    // Sem caracteres de controle, exceto quebras de linha e tabulação.
    private static final String PLAIN_TEXT = "(?s)[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*";
    private static final String PLAIN_TEXT_MESSAGE = "tem caracteres não permitidos";

    private final ReportService reports;
    private final ModerationService moderation;
    private final AuditLogService auditLog;

    ModerationController(ReportService reports, ModerationService moderation, AuditLogService auditLog) {
        this.reports = reports;
        this.moderation = moderation;
        this.auditLog = auditLog;
    }

    record ReportRequest(@NotNull ReportTargetType targetType, @NotNull UUID targetId, @NotNull ReportReason reason,
                         @Size(max = 1000) @Pattern(regexp = PLAIN_TEXT, message = PLAIN_TEXT_MESSAGE) String details) {
    }

    record CloseReportRequest(@NotNull ReportStatus status,
                              @Size(max = 1000) @Pattern(regexp = PLAIN_TEXT, message = PLAIN_TEXT_MESSAGE) String note) {
    }

    record ActionRequest(@NotBlank @Size(max = 500) @Pattern(regexp = PLAIN_TEXT, message = PLAIN_TEXT_MESSAGE) String reason) {
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    ReportReceipt report(@AuthenticationPrincipal SessionUser principal, @Valid @RequestBody ReportRequest body,
                         HttpServletRequest request) {
        return reports.create(new ReportService.NewReport(body.targetType(), body.targetId(), body.reason(), body.details()),
                principal, ClientAddress.of(request));
    }

    @GetMapping("/moderation/reports")
    PageResponse<ReportView> reports(@RequestParam(required = false) ReportStatus status,
                                     @RequestParam(required = false) ReportTargetType targetType,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size,
                                     @RequestParam(defaultValue = "createdAt") String sort) {
        return reports.list(status, targetType, PageRequests.of(page, size, sort, Set.of("createdAt")));
    }

    @GetMapping("/moderation/reports/{id}")
    ReportView report(@PathVariable UUID id) {
        return reports.get(id);
    }

    @PatchMapping("/moderation/reports/{id}")
    ReportView closeReport(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id,
                           @Valid @RequestBody CloseReportRequest body) {
        return reports.close(id, body.status(), body.note(), principal);
    }

    /**
     * Exige bookId ou userId: uma lista de todos os comentários da plataforma não ajuda a moderar nada.
     */
    @GetMapping("/moderation/comments")
    PageResponse<ModeratedComment> comments(@RequestParam(required = false) UUID bookId,
                                            @RequestParam(required = false) UUID userId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(defaultValue = "-createdAt") String sort) {
        Pageable pageable = PageRequests.of(page, size, sort, Set.of("createdAt"));
        if ((bookId == null) == (userId == null)) {
            throw ApiException.badRequest("INVALID_FILTER", "Informe bookId ou userId.");
        }
        return bookId != null ? moderation.commentsOnBook(bookId, pageable) : moderation.commentsByUser(userId, pageable);
    }

    @PostMapping("/moderation/books/{id}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void hideBook(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id,
                  @Valid @RequestBody ActionRequest body) {
        moderation.hideBook(id, body.reason(), principal);
    }

    @PostMapping("/moderation/books/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void restoreBook(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id,
                     @Valid @RequestBody ActionRequest body) {
        moderation.restoreBook(id, body.reason(), principal);
    }

    @PostMapping("/moderation/chapters/{id}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void hideChapter(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id,
                     @Valid @RequestBody ActionRequest body) {
        moderation.hideChapter(id, body.reason(), principal);
    }

    @PostMapping("/moderation/chapters/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void restoreChapter(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id,
                        @Valid @RequestBody ActionRequest body) {
        moderation.restoreChapter(id, body.reason(), principal);
    }

    @PostMapping("/moderation/comments/{id}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void hideComment(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id,
                     @Valid @RequestBody ActionRequest body) {
        moderation.hideComment(id, body.reason(), principal);
    }

    @PostMapping("/moderation/comments/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void restoreComment(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id,
                        @Valid @RequestBody ActionRequest body) {
        moderation.restoreComment(id, body.reason(), principal);
    }

    @GetMapping("/admin/audit-log")
    PageResponse<AuditLogService.AuditLogEntry> auditLog(@RequestParam(required = false) UUID actorId,
                                                         @RequestParam(required = false) String resource,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return auditLog.list(actorId, resource, PageRequests.of(page, size, "-createdAt", Set.of("createdAt")));
    }
}
