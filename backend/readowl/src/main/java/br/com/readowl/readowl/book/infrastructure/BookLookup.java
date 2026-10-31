package br.com.readowl.readowl.book.infrastructure;

import br.com.readowl.readowl.book.domain.Book;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Consultas de leitura pública, que sempre trazem o autor.
 */
public interface BookLookup extends Repository<Book, UUID> {

    @EntityGraph(attributePaths = "author")
    Optional<Book> findBySlug(String slug);

    @EntityGraph(attributePaths = "author")
    Optional<Book> findById(UUID id);
}
