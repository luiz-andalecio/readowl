package br.com.readowl.readowl.book.application;

import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.book.infrastructure.BookLookup;
import br.com.readowl.readowl.book.infrastructure.BookSlugHistory;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.user.application.SessionUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Único ponto que decide se uma obra é pública: publicada, de autor ativo e, se for adulta, só para quem está logado.
 * Vale para todos, inclusive o próprio autor e administradores, que veem o restante pelas rotas de gerenciamento.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PublicBooks {

    private final BookLookup books;
    private final BookSlugHistory slugHistory;

    PublicBooks(BookLookup books, BookSlugHistory slugHistory) {
        this.books = books;
        this.slugHistory = slugHistory;
    }

    public Book requireBySlug(String slug) {
        Optional<Book> current = books.findBySlug(slug);
        if (current.isEmpty()) {
            throwIfMoved(slug);
        }
        return current.filter(PublicBooks::isPublic).orElseThrow(PublicBooks::notFound);
    }

    public Book requireById(UUID id) {
        return books.findById(id).filter(PublicBooks::isPublic).orElseThrow(PublicBooks::notFound);
    }

    public static boolean isPublic(Book book) {
        return book.isPublished() && book.getAuthor().isActive() && (!book.isAdult() || viewerIsLoggedIn());
    }

    public static boolean viewerIsLoggedIn() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof SessionUser;
    }

    /**
     * O frontend responde 301 ao navegador, com o novo endereço. Sem o erro próprio, o HttpClient seguiria um redirecionamento
     * sozinho, e o SSR nunca saberia do endereço novo. Uma obra fora do ar responde como inexistente.
     */
    private void throwIfMoved(String oldSlug) {
        slugHistory.findBookId(oldSlug).stream().findFirst().flatMap(books::findById).filter(PublicBooks::isPublic)
                .ifPresent(book -> {
                    throw ApiException.notFound("BOOK_MOVED", "Esta obra mudou de endereço.")
                            .withProperty("currentSlug", book.getSlug());
                });
    }

    private static ApiException notFound() {
        return ApiException.notFound("BOOK_NOT_FOUND", "Obra não encontrada.");
    }
}
