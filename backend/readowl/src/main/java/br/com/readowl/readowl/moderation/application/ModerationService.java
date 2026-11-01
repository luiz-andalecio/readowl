package br.com.readowl.readowl.moderation.application;

import br.com.readowl.readowl.community.application.CommentModeration;
import br.com.readowl.readowl.community.application.CommentModeration.ModeratedComment;
import br.com.readowl.readowl.publishing.application.PublicationService;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.audit.AuditTrail;
import br.com.readowl.readowl.user.application.SessionUser;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Ações sobre conteúdo. A permissão vem do papel exigido pelas rotas, e toda ação fica na auditoria com o motivo.
 * Suspender contas não está aqui: editar usuários é exclusivo do root, em /admin/users.
 */
@Service
@Transactional
public class ModerationService {

    private final PublicationService publication;
    private final CommentModeration comments;
    private final AuditTrail audit;

    ModerationService(PublicationService publication, CommentModeration comments, AuditTrail audit) {
        this.publication = publication;
        this.comments = comments;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<ModeratedComment> commentsOnBook(UUID bookId, Pageable pageable) {
        return comments.listOnBook(bookId, pageable);
    }

    @Transactional(readOnly = true)
    public PageResponse<ModeratedComment> commentsByUser(UUID userId, Pageable pageable) {
        return comments.listByUser(userId, pageable);
    }

    public void hideBook(UUID id, String reason, SessionUser actor) {
        publication.hideBook(id);
        audit.record(actor.id(), "BOOK_HIDDEN", "books/" + id, Map.of("reason", reason.trim()));
    }

    public void restoreBook(UUID id, String reason, SessionUser actor) {
        publication.restoreBook(id, actor);
        audit.record(actor.id(), "BOOK_RESTORED", "books/" + id, Map.of("reason", reason.trim()));
    }

    public void hideChapter(UUID id, String reason, SessionUser actor) {
        publication.hideChapter(id);
        audit.record(actor.id(), "CHAPTER_HIDDEN", "chapters/" + id, Map.of("reason", reason.trim()));
    }

    public void restoreChapter(UUID id, String reason, SessionUser actor) {
        publication.restoreChapter(id, actor);
        audit.record(actor.id(), "CHAPTER_RESTORED", "chapters/" + id, Map.of("reason", reason.trim()));
    }

    public void hideComment(UUID id, String reason, SessionUser actor) {
        comments.hide(id);
        audit.record(actor.id(), "COMMENT_HIDDEN", "comments/" + id, Map.of("reason", reason.trim()));
    }

    public void restoreComment(UUID id, String reason, SessionUser actor) {
        comments.restore(id, actor);
        audit.record(actor.id(), "COMMENT_RESTORED", "comments/" + id, Map.of("reason", reason.trim()));
    }
}
