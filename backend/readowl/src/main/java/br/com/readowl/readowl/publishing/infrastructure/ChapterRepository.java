package br.com.readowl.readowl.publishing.infrastructure;

import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.publishing.domain.ChapterState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChapterRepository extends JpaRepository<Chapter, UUID> {

    /**
     * Linha do índice público: não carrega o texto das revisões.
     */
    record PublishedChapterRow(UUID id, String title, String slug, UUID volumeId, String volumeTitle,
                               Instant publishedAt) {
    }

    record PublishedVolumeRow(UUID id, String title) {
    }

    record ChapterSlugRow(UUID id, String slug) {
    }

    /**
     * Ordem de leitura: primeiro os capítulos sem volume, depois cada volume em ordem.
     */
    @Query(value = """
            select new br.com.readowl.readowl.publishing.infrastructure.ChapterRepository$PublishedChapterRow(
                c.id, c.title, c.slug, v.id, v.title, r.createdAt)
            from Chapter c left join c.volume v join c.publishedRevision r
            where c.book.id = :bookId and c.state = br.com.readowl.readowl.publishing.domain.ChapterState.PUBLISHED
            order by v.displayOrder asc nulls first, c.displayOrder asc
            """, countQuery = """
            select count(c) from Chapter c
            where c.book.id = :bookId and c.state = br.com.readowl.readowl.publishing.domain.ChapterState.PUBLISHED
            """)
    Page<PublishedChapterRow> findPublishedRows(UUID bookId, Pageable pageable);

    /**
     * Volumes sem capítulos publicados podem ter títulos ainda privados.
     */
    @Query("""
            select new br.com.readowl.readowl.publishing.infrastructure.ChapterRepository$PublishedVolumeRow(v.id, v.title)
            from Volume v
            where v.book.id = :bookId and exists (
                select 1 from Chapter c
                where c.volume = v and c.state = br.com.readowl.readowl.publishing.domain.ChapterState.PUBLISHED)
            order by v.displayOrder asc
            """)
    List<PublishedVolumeRow> findPublishedVolumes(UUID bookId);

    /**
     * Os slugs que poderiam colidir com um novo: o próprio e os numerados a partir dele.
     */
    @Query("""
            select new br.com.readowl.readowl.publishing.infrastructure.ChapterRepository$ChapterSlugRow(c.id, c.slug)
            from Chapter c where c.book.id = :bookId and (c.slug = :slug or c.slug like :numberedSlug)
            """)
    List<ChapterSlugRow> findSimilarSlugs(UUID bookId, String slug, String numberedSlug);

    /**
     * Capítulo para leitura: só o HTML da revisão, que também guarda o documento e o texto puro.
     */
    record ChapterContentRow(UUID id, String title, String slug, UUID volumeId, String volumeTitle, String html,
                             Instant publishedAt) {
    }

    @Query("""
            select new br.com.readowl.readowl.publishing.infrastructure.ChapterRepository$ChapterContentRow(
                c.id, c.title, c.slug, v.id, v.title, r.html, r.createdAt)
            from Chapter c left join c.volume v join c.publishedRevision r
            where c.book.id = :bookId and c.slug = :slug
              and c.state = br.com.readowl.readowl.publishing.domain.ChapterState.PUBLISHED
            """)
    Optional<ChapterContentRow> findPublishedContent(UUID bookId, String slug);

    long countByBookId(UUID bookId);

    Optional<Chapter> findByIdAndBookId(UUID id, UUID bookId);

    @EntityGraph(attributePaths = "volume")
    List<Chapter> findByBookIdOrderByDisplayOrder(UUID bookId);

    @EntityGraph(attributePaths = "volume")
    List<Chapter> findByBookIdAndIdIn(UUID bookId, Collection<UUID> ids);

    List<Chapter> findByBookIdAndVolumeId(UUID bookId, UUID volumeId);

    List<Chapter> findByBookIdAndVolumeIsNull(UUID bookId);

    boolean existsByVolumeId(UUID volumeId);

    @Query("select c.book.id from Chapter c where c.id = :id")
    Optional<UUID> findBookIdById(UUID id);

    long countByBookIdAndState(UUID bookId, ChapterState state);

    @Query("select coalesce(max(c.displayOrder), 0) from Chapter c where c.book.id = :bookId and c.volume.id = :volumeId")
    int findMaxOrderInVolume(UUID bookId, UUID volumeId);

    @Query("select coalesce(max(c.displayOrder), 0) from Chapter c where c.book.id = :bookId and c.volume is null")
    int findMaxOrderWithoutVolume(UUID bookId);
}
