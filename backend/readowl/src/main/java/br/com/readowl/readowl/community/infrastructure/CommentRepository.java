package br.com.readowl.readowl.community.infrastructure;

import br.com.readowl.readowl.community.domain.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    /**
     * Um comentário principal aparece se estiver visível ou se ainda tiver respostas visíveis, para não
     * sumir com a conversa. Comentários de contas suspensas não aparecem, e voltam se a conta for reativada.
     */
    String THREAD_IS_VISIBLE = """
             and c.parent is null
             and ((c.deletedAt is null and u.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE)
                  or exists (select 1 from Comment r join r.user ru
                             where r.parent = c and r.deletedAt is null
                               and ru.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE))
            """;

    record CommentCount(UUID commentId, long total) {
    }

    @Query(value = "select c from Comment c join fetch c.user u where c.book.id = :bookId and c.chapter is null"
            + THREAD_IS_VISIBLE,
            countQuery = "select count(c) from Comment c join c.user u where c.book.id = :bookId and c.chapter is null"
                    + THREAD_IS_VISIBLE)
    Page<Comment> findVisibleOnBook(UUID bookId, Pageable pageable);

    @Query(value = "select c from Comment c join fetch c.user u where c.chapter.id = :chapterId" + THREAD_IS_VISIBLE,
            countQuery = "select count(c) from Comment c join c.user u where c.chapter.id = :chapterId"
                    + THREAD_IS_VISIBLE)
    Page<Comment> findVisibleOnChapter(UUID chapterId, Pageable pageable);

    @Query(value = """
            select c from Comment c join fetch c.user u
            where c.parent.id = :parentId and c.deletedAt is null
              and u.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE
            """, countQuery = """
            select count(c) from Comment c join c.user u
            where c.parent.id = :parentId and c.deletedAt is null
              and u.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE
            """)
    Page<Comment> findVisibleReplies(UUID parentId, Pageable pageable);

    @Query("""
            select new br.com.readowl.readowl.community.infrastructure.CommentRepository$CommentCount(c.parent.id, count(c))
            from Comment c join c.user u
            where c.parent.id in :parentIds and c.deletedAt is null
              and u.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE
            group by c.parent.id
            """)
    List<CommentCount> countVisibleReplies(Collection<UUID> parentIds);

    @Query(value = "select c from Comment c join fetch c.user where c.book.id = :bookId",
            countQuery = "select count(c) from Comment c where c.book.id = :bookId")
    Page<Comment> findAllOnBook(UUID bookId, Pageable pageable);

    @Query(value = "select c from Comment c join fetch c.user where c.user.id = :userId",
            countQuery = "select count(c) from Comment c where c.user.id = :userId")
    Page<Comment> findAllByUser(UUID userId, Pageable pageable);

    @Query("select c from Comment c where c.id = :id and c.deletedAt is null")
    Optional<Comment> findNotDeleted(UUID id);

    /**
     * O bloqueio dura até o fim da transação e serializa só os comentários de um mesmo usuário, para que
     * dois envios simultâneos do mesmo texto não passem juntos pela verificação de repetição.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(CAST(:userId AS text), 0))) AS locked",
            nativeQuery = true)
    int lockCommentsOf(UUID userId);

    @Query("""
            select count(c) > 0 from Comment c
            where c.user.id = :userId and c.book.id = :bookId and c.chapter is null and c.parent is null
              and c.content = :content and c.deletedAt is null and c.createdAt > :since
            """)
    boolean existsRecentOnBook(UUID userId, UUID bookId, String content, Instant since);

    @Query("""
            select count(c) > 0 from Comment c
            where c.user.id = :userId and c.chapter.id = :chapterId and c.parent is null
              and c.content = :content and c.deletedAt is null and c.createdAt > :since
            """)
    boolean existsRecentOnChapter(UUID userId, UUID chapterId, String content, Instant since);

    @Query("""
            select count(c) > 0 from Comment c
            where c.user.id = :userId and c.parent.id = :parentId
              and c.content = :content and c.deletedAt is null and c.createdAt > :since
            """)
    boolean existsRecentReply(UUID userId, UUID parentId, String content, Instant since);
}
