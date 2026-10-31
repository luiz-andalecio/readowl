package br.com.readowl.readowl.library.infrastructure;

import br.com.readowl.readowl.library.domain.ReadingProgress;
import br.com.readowl.readowl.library.domain.ReadingProgressId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReadingProgressRepository extends JpaRepository<ReadingProgress, ReadingProgressId> {

    /**
     * A última leitura vale, mesmo voltando a um capítulo anterior.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO reading_progress (user_id, book_id, chapter_id, percent, updated_at)
            VALUES (:userId, :bookId, :chapterId, :percent, now())
            ON CONFLICT (user_id, book_id) DO UPDATE
            SET chapter_id = EXCLUDED.chapter_id, percent = EXCLUDED.percent, updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    void record(UUID userId, UUID bookId, UUID chapterId, int percent);

    @Query("select p from ReadingProgress p join fetch p.chapter where p.id.userId = :userId and p.id.bookId in :bookIds")
    List<ReadingProgress> findByUserAndBooks(UUID userId, Collection<UUID> bookIds);
}
