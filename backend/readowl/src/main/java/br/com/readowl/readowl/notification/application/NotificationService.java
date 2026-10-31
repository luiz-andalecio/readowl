package br.com.readowl.readowl.notification.application;

import br.com.readowl.readowl.notification.domain.Notification;
import br.com.readowl.readowl.notification.domain.NotificationType;
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

    NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    public record NotificationLink(UUID id, String title, String slug) {
    }

    public record NotificationView(UUID id, NotificationType type, boolean read, NotificationLink book,
                                   NotificationLink chapter, Instant createdAt) {

        static NotificationView from(Notification notification) {
            return new NotificationView(notification.getId(), notification.getType(), notification.getReadAt() != null,
                    new NotificationLink(notification.getBook().getId(), notification.getBook().getTitle(),
                            notification.getBook().getSlug()),
                    new NotificationLink(notification.getChapter().getId(), notification.getChapter().getTitle(),
                            notification.getChapter().getSlug()),
                    notification.getCreatedAt());
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

    public void markAllRead(SessionUser principal) {
        notifications.markAllRead(principal.id());
    }
}
