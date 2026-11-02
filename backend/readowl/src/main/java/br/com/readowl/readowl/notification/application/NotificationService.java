package br.com.readowl.readowl.notification.application;

import br.com.readowl.readowl.notification.domain.Notification;
import br.com.readowl.readowl.notification.domain.NotificationType;
import br.com.readowl.readowl.notification.infrastructure.NotificationQueue;
import br.com.readowl.readowl.notification.infrastructure.NotificationRepository;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.user.application.SessionUser;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * O usuário vem sempre da sessão: não há como consultar ou alterar as notificações de outra pessoa.
 */
@Service
@Transactional
public class NotificationService {

    private final NotificationRepository notifications;
    private final NotificationQueue queue;

    NotificationService(NotificationRepository notifications, NotificationQueue queue) {
        this.notifications = notifications;
        this.queue = queue;
    }

    public record NotificationLink(UUID id, String title, String slug) {
    }

    /**
     * @param book ausente quando o aviso não trata de uma obra
     * @param chapter ausente quando o aviso não trata de um capítulo
     * @param message só nos avisos de moderação: o motivo informado
     */
    public record NotificationView(UUID id, NotificationType type, boolean read, NotificationLink book,
                                   NotificationLink chapter, String message, Instant createdAt) {

        static NotificationView from(Notification notification) {
            return new NotificationView(notification.getId(), notification.getType(), notification.getReadAt() != null,
                    notification.getBook() == null ? null : new NotificationLink(notification.getBook().getId(),
                            notification.getBook().getTitle(), notification.getBook().getSlug()),
                    notification.getChapter() == null ? null : new NotificationLink(notification.getChapter().getId(),
                            notification.getChapter().getTitle(), notification.getChapter().getSlug()),
                    notification.getMessage(), notification.getCreatedAt());
        }
    }

    public record UnreadCount(long unread) {
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationView> list(SessionUser principal, boolean unreadOnly, Pageable pageable) {
        return PageResponse.from(notifications.findVisible(principal.id(), unreadOnly, pageable).map(NotificationView::from));
    }

    @Transactional(readOnly = true)
    public UnreadCount unread(SessionUser principal) {
        return new UnreadCount(notifications.countUnread(principal.id()));
    }

    public void markRead(UUID id, boolean read, SessionUser principal) {
        if (notifications.markRead(id, principal.id(), read) == 0) {
            throw ApiException.notFound("NOTIFICATION_NOT_FOUND", "Notificação não encontrada.");
        }
    }

    /**
     * Na mesma transação da decisão: uma não existe sem a outra.
     */
    public void notifyModeration(UUID userId, NotificationType type, UUID bookId, UUID chapterId, String message) {
        queue.insertNotice(userId, type.name(), bookId, chapterId, message);
    }

    public void markAllRead(SessionUser principal) {
        notifications.markAllRead(principal.id());
    }
}
