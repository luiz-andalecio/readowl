package br.com.readowl.readowl.publishing.infrastructure;

import br.com.readowl.readowl.publishing.domain.ChapterRevision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

public interface ChapterRevisionRepository extends JpaRepository<ChapterRevision, UUID> {

    /**
     * Linha do histórico: uma página de revisões inteiras poderia ocupar centenas de megabytes.
     */
    record RevisionRow(UUID id, int revisionNumber, int formatVersion, String createdByUsername, Instant createdAt) {
    }

    @Query("select coalesce(max(r.revisionNumber), 0) from ChapterRevision r where r.chapter.id = :chapterId")
    int findMaxRevisionNumber(UUID chapterId);

    /**
     * Tamanho gravado, já comprimido pelo banco. pg_column_size não lê os textos, só os tamanhos deles.
     */
    @Query(value = """
            SELECT CAST(
                COALESCE((SELECT sum(pg_column_size(r.document) + pg_column_size(r.plain_text) + pg_column_size(r.html))
                          FROM chapter_revisions r
                          JOIN chapters c ON c.id = r.chapter_id
                          JOIN books b ON b.id = c.book_id
                          WHERE b.author_id = :authorId), 0)
                + COALESCE((SELECT sum(pg_column_size(d.document))
                            FROM chapter_drafts d
                            JOIN chapters c ON c.id = d.chapter_id
                            JOIN books b ON b.id = c.book_id
                            WHERE b.author_id = :authorId), 0) AS bigint)
            """, nativeQuery = true)
    long sumStoredBytesOfAuthor(UUID authorId);

    @Query(value = """
            select new br.com.readowl.readowl.publishing.infrastructure.ChapterRevisionRepository$RevisionRow(
                r.id, r.revisionNumber, r.formatVersion, u.username, r.createdAt)
            from ChapterRevision r join r.createdBy u
            where r.chapter.id = :chapterId
            """, countQuery = "select count(r) from ChapterRevision r where r.chapter.id = :chapterId")
    Page<RevisionRow> findRowsByChapterId(UUID chapterId, Pageable pageable);
}
