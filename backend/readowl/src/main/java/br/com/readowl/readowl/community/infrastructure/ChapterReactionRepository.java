package br.com.readowl.readowl.community.infrastructure;

import br.com.readowl.readowl.community.domain.ChapterReaction;
import br.com.readowl.readowl.community.domain.ChapterReactionId;
import br.com.readowl.readowl.community.domain.ReactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ChapterReactionRepository extends JpaRepository<ChapterReaction, ChapterReactionId> {

    record ReactionCount(ReactionType type, long total) {
    }

    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO chapter_reactions (user_id, chapter_id, type, created_at, updated_at)
            VALUES (:userId, :chapterId, :type, now(), now())
            ON CONFLICT (user_id, chapter_id) DO UPDATE SET type = EXCLUDED.type, updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    void record(UUID userId, UUID chapterId, String type);

    /**
     * Remover duas vezes, ou em duas abas ao mesmo tempo, não é erro.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM chapter_reactions WHERE user_id = :userId AND chapter_id = :chapterId", nativeQuery = true)
    void remove(UUID userId, UUID chapterId);

    /**
     * Contas suspensas deixam de contar, e voltam se forem reativadas.
     */
    @Query("""
            select new br.com.readowl.readowl.community.infrastructure.ChapterReactionRepository$ReactionCount(r.type, count(r))
            from ChapterReaction r, User u
            where r.id.chapterId = :chapterId and u.id = r.id.userId
              and u.status = br.com.readowl.readowl.user.domain.UserStatus.ACTIVE
            group by r.type
            """)
    List<ReactionCount> countByType(UUID chapterId);
}
