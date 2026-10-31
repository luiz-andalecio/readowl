package br.com.readowl.readowl.publishing.infrastructure;

import br.com.readowl.readowl.publishing.domain.Chapter;
import br.com.readowl.readowl.publishing.domain.ChapterState;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChapterRepository extends JpaRepository<Chapter, UUID> {

    @EntityGraph(attributePaths = "volume")
    List<Chapter> findByBookIdOrderByDisplayOrder(UUID bookId);

    @EntityGraph(attributePaths = "volume")
    List<Chapter> findByBookIdAndIdIn(UUID bookId, Collection<UUID> ids);

    List<Chapter> findByBookIdAndVolumeId(UUID bookId, UUID volumeId);

    List<Chapter> findByBookIdAndVolumeIsNull(UUID bookId);

    boolean existsByVolumeId(UUID volumeId);

    boolean existsByBookIdAndSlug(UUID bookId, String slug);

    boolean existsByBookIdAndSlugAndIdNot(UUID bookId, String slug, UUID id);

    @Query("select c.book.id from Chapter c where c.id = :id")
    Optional<UUID> findBookIdById(UUID id);

    long countByBookIdAndState(UUID bookId, ChapterState state);

    @Query("select coalesce(max(c.displayOrder), 0) from Chapter c where c.book.id = :bookId and c.volume.id = :volumeId")
    int findMaxOrderInVolume(UUID bookId, UUID volumeId);

    @Query("select coalesce(max(c.displayOrder), 0) from Chapter c where c.book.id = :bookId and c.volume is null")
    int findMaxOrderWithoutVolume(UUID bookId);
}
