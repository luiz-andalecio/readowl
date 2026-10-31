package br.com.readowl.readowl.notification.infrastructure;

import br.com.readowl.readowl.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Sem o que saiu do ar: o link da notificação levaria a um 404, e a contagem de não lidas não bateria.
     */
    @Query(value = """
            select n from Notification n join fetch n.book b join fetch n.chapter c join b.author a
            where n.userId = :userId and (:unreadOnly = false or n.readAt is null)
              and b.publicationState = br.com.readowl.readowl.book.domain.PublicationState.PUBLISHED
              and c.state = br.com.readowl.readowl.publishing.domain.ChapterState.PUBLISHED
              and a.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE
            """, countQuery = """
            select count(n) from Notification n join n.book b join n.chapter c join b.author a
            where n.userId = :userId and (:unreadOnly = false or n.readAt is null)
              and b.publicationState = br.com.readowl.readowl.book.domain.PublicationState.PUBLISHED
              and c.state = br.com.readowl.readowl.publishing.domain.ChapterState.PUBLISHED
              and a.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE
            """)
    Page<Notification> findVisible(UUID userId, boolean unreadOnly, Pageable pageable);

    @Query("""
            select count(n) from Notification n join n.book b join n.chapter c join b.author a
            where n.userId = :userId and n.readAt is null
              and b.publicationState = br.com.readowl.readowl.book.domain.PublicationState.PUBLISHED
              and c.state = br.com.readowl.readowl.publishing.domain.ChapterState.PUBLISHED
              and a.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE
            """)
    long countUnread(UUID userId);

    /**
     * Mantém a data da primeira leitura.
     *
     * @return 0 se a notificação não existir ou for de outra pessoa
     */
    @Modifying
    @Query(value = """
            UPDATE notifications SET read_at = CASE WHEN :read THEN COALESCE(read_at, now()) END
            WHERE id = :id AND user_id = :userId
            """, nativeQuery = true)
    int markRead(UUID id, UUID userId, boolean read);

    @Modifying
    @Query(value = "UPDATE notifications SET read_at = now() WHERE user_id = :userId AND read_at IS NULL", nativeQuery = true)
    void markAllRead(UUID userId);
}
