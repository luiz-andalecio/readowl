package br.com.readowl.readowl.moderation.application;

import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.community.application.CommentModeration;
import br.com.readowl.readowl.community.application.CommentModeration.ModeratedComment;
import br.com.readowl.readowl.community.domain.Comment;
import br.com.readowl.readowl.notification.application.NotificationService;
import br.com.readowl.readowl.notification.domain.NotificationType;
import br.com.readowl.readowl.publishing.application.PublicationService;
import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.audit.AuditTrail;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.user.application.AvatarService;
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
    private final NotificationService notifications;
    private final AvatarService avatars;

    ModerationService(PublicationService publication, CommentModeration comments, AuditTrail audit,
                      NotificationService notifications, AvatarService avatars) {
        this.avatars = avatars;
        this.notifications = notifications;
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

    /**
     * Quem escreveu precisa saber o que saiu do ar e por quê. Por isso, o motivo é escrito para a pessoa ler. Quem modera o
     * próprio conteúdo já sabe.
     */
    private void notifyAuthor(Book book, NotificationType type, String reason, SessionUser actor) {
        notify(book.getAuthor().getId(), type, book.getId(), null, reason, actor);
    }

    private void notifyAuthor(Chapter chapter, NotificationType type, String reason, SessionUser actor) {
        notify(chapter.getBook().getAuthor().getId(), type, chapter.getBook().getId(), chapter.getId(), reason, actor);
    }

    private void notifyAuthor(Comment comment, NotificationType type, String reason, SessionUser actor) {
        notify(comment.getUser().getId(), type, comment.getBook().getId(),
                comment.getChapter() == null ? null : comment.getChapter().getId(), reason, actor);
    }

    private void notify(UUID owner, NotificationType type, UUID bookId, UUID chapterId, String reason, SessionUser actor) {
        if (!owner.equals(actor.id())) {
            notifications.notifyModeration(owner, type, bookId, chapterId, reason.trim());
        }
    }

    /**
     * Retira só a imagem imprópria, sem tirar a obra do ar.
     */
    public void removeCover(UUID id, String reason, SessionUser actor) {
        Book book = publication.removeCover(id);
        notifyAuthor(book, NotificationType.CONTENT_HIDDEN, reason, actor);
        audit.record(actor.id(), "COVER_REMOVED", "books/" + id, Map.of("reason", reason.trim()));
    }

    public void removeAvatar(UUID userId, String reason, SessionUser actor) {
        actor.requireIndependentReview(userId);
        if (!avatars.remove(userId)) {
            throw ApiException.conflict("NO_AVATAR", "Esta conta não tem foto.");
        }
        notify(userId, NotificationType.CONTENT_HIDDEN, null, null, reason, actor);
        audit.record(actor.id(), "AVATAR_REMOVED", "users/" + userId, Map.of("reason", reason.trim()));
    }

    public void hideBook(UUID id, String reason, SessionUser actor) {
        notifyAuthor(publication.hideBook(id), NotificationType.CONTENT_HIDDEN, reason, actor);
        audit.record(actor.id(), "BOOK_HIDDEN", "books/" + id, Map.of("reason", reason.trim()));
    }

    public void restoreBook(UUID id, String reason, SessionUser actor) {
        notifyAuthor(publication.restoreBook(id, actor), NotificationType.CONTENT_RESTORED, reason, actor);
        audit.record(actor.id(), "BOOK_RESTORED", "books/" + id, Map.of("reason", reason.trim()));
    }

    public void hideChapter(UUID id, String reason, SessionUser actor) {
        notifyAuthor(publication.hideChapter(id), NotificationType.CONTENT_HIDDEN, reason, actor);
        audit.record(actor.id(), "CHAPTER_HIDDEN", "chapters/" + id, Map.of("reason", reason.trim()));
    }

    public void restoreChapter(UUID id, String reason, SessionUser actor) {
        notifyAuthor(publication.restoreChapter(id, actor), NotificationType.CONTENT_RESTORED, reason, actor);
        audit.record(actor.id(), "CHAPTER_RESTORED", "chapters/" + id, Map.of("reason", reason.trim()));
    }

    public void hideComment(UUID id, String reason, SessionUser actor) {
        notifyAuthor(comments.hide(id), NotificationType.CONTENT_HIDDEN, reason, actor);
        audit.record(actor.id(), "COMMENT_HIDDEN", "comments/" + id, Map.of("reason", reason.trim()));
    }

    public void restoreComment(UUID id, String reason, SessionUser actor) {
        notifyAuthor(comments.restore(id, actor), NotificationType.CONTENT_RESTORED, reason, actor);
        audit.record(actor.id(), "COMMENT_RESTORED", "comments/" + id, Map.of("reason", reason.trim()));
    }
}
