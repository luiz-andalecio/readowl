package br.com.readowl.readowl.publishing.infrastructure;

import br.com.readowl.readowl.publishing.domain.Volume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VolumeRepository extends JpaRepository<Volume, UUID> {

    List<Volume> findByBookIdOrderByDisplayOrder(UUID bookId);

    long countByBookId(UUID bookId);

    Optional<Volume> findByIdAndBookId(UUID id, UUID bookId);

    @Query("select v.book.id from Volume v where v.id = :id")
    Optional<UUID> findBookIdById(UUID id);

    @Query("select coalesce(max(v.displayOrder), 0) from Volume v where v.book.id = :bookId")
    int findMaxOrder(UUID bookId);
}
