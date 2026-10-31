package br.com.readowl.readowl.publishing.infrastructure;

import br.com.readowl.readowl.publishing.domain.ChapterRevision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface ChapterRevisionRepository extends JpaRepository<ChapterRevision, UUID> {

    @Query("select coalesce(max(r.revisionNumber), 0) from ChapterRevision r where r.chapter.id = :chapterId")
    int findMaxRevisionNumber(UUID chapterId);

    @EntityGraph(attributePaths = "createdBy")
    Page<ChapterRevision> findByChapterId(UUID chapterId, Pageable pageable);
}
