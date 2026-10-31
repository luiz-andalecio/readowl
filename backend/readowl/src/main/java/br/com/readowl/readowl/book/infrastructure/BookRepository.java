package br.com.readowl.readowl.book.infrastructure;

import br.com.readowl.readowl.book.domain.Book;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface BookRepository extends JpaRepository<Book, UUID> {

    /**
     * Serializa alterações na estrutura da obra, como numerar capítulos e publicar.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Book b where b.id = :id")
    Optional<Book> findLockedById(UUID id);

    boolean existsBySlug(String slug);

    @EntityGraph(attributePaths = "author")
    Page<Book> findByAuthorId(UUID authorId, Pageable pageable);

    @EntityGraph(attributePaths = "author")
    Page<Book> findAllBy(Pageable pageable);
}
