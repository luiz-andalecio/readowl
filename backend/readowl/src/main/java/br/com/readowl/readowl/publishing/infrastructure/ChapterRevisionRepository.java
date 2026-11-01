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

    @Query(value = """
            select new br.com.readowl.readowl.publishing.infrastructure.ChapterRevisionRepository$RevisionRow(
                r.id, r.revisionNumber, r.formatVersion, u.username, r.createdAt)
            from ChapterRevision r join r.createdBy u
            where r.chapter.id = :chapterId
            """, countQuery = "select count(r) from ChapterRevision r where r.chapter.id = :chapterId")
    Page<RevisionRow> findRowsByChapterId(UUID chapterId, Pageable pageable);
}
