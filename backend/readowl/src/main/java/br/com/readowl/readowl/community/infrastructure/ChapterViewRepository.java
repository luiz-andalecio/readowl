package br.com.readowl.readowl.community.infrastructure;

import br.com.readowl.readowl.community.domain.ChapterView;
import br.com.readowl.readowl.community.domain.ChapterViewId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface ChapterViewRepository extends JpaRepository<ChapterView, ChapterViewId> {

    /**
     * Um único comando decide e conta, o que vale entre instâncias: entre pedidos simultâneos, só o que
     * grava a linha incrementa os contadores. A obra é atualizada antes do capítulo, na mesma ordem das
     * operações de publicação, que bloqueiam a obra primeiro. A ordem inversa causaria deadlock.
     *
     * @return 1 se a visita contou
     */
    @Modifying
    @Query(value = """
            WITH counted AS (
                INSERT INTO chapter_views (user_id, chapter_id, last_counted_at)
                VALUES (:userId, :chapterId, now())
                ON CONFLICT (user_id, chapter_id) DO UPDATE SET last_counted_at = EXCLUDED.last_counted_at
                WHERE chapter_views.last_counted_at <= now() - interval '2 minutes'
                RETURNING chapter_id
            ), book_counted AS (
                UPDATE books SET view_count = view_count + 1
                WHERE id = :bookId AND EXISTS (SELECT 1 FROM counted)
                RETURNING id
            )
            UPDATE chapters SET view_count = view_count + 1
            WHERE id = :chapterId AND EXISTS (SELECT 1 FROM book_counted)
            """, nativeQuery = true)
    int count(UUID userId, UUID chapterId, UUID bookId);
}
