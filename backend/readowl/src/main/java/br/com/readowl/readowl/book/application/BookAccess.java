package br.com.readowl.readowl.book.application;

import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.book.infrastructure.BookRepository;
import br.com.readowl.readowl.shared.audit.AuditContext;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.user.application.SessionUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Único ponto que decide quem altera uma obra: o autor, um administrador ou o root.
 * Para os demais, uma obra fora do ar responde como inexistente, sem revelar que existe.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class BookAccess {

    private final BookRepository books;

    BookAccess(BookRepository books) {
        this.books = books;
    }

    public Book requireManageable(UUID bookId, SessionUser principal) {
        return checked(books.findById(bookId).orElseThrow(BookAccess::notFound), principal);
    }

    /**
     * Mantém a linha da obra bloqueada até o fim da transação.
     */
    public Book requireManageableLocked(UUID bookId, SessionUser principal) {
        return checked(books.findLockedById(bookId).orElseThrow(BookAccess::notFound), principal);
    }

    /**
     * Bloqueia a obra sem verificar permissão. Deve vir antes de ler a obra e seus capítulos, para que a
     * decisão seguinte use dados atuais.
     */
    public void lock(UUID bookId) {
        books.findLockedById(bookId);
    }

    /**
     * Para a moderação, cuja permissão vem do papel exigido pela rota.
     */
    public Book requireLocked(UUID bookId) {
        return books.findLockedById(bookId).orElseThrow(BookAccess::notFound);
    }

    public static boolean canManage(Book book, SessionUser principal) {
        if (book.getAuthor().getId().equals(principal.id())) {
            return true;
        }
        if (principal.isAdministrator()) {
            AuditContext.markOverride();
            return true;
        }
        return false;
    }

    private static Book checked(Book book, SessionUser principal) {
        if (canManage(book, principal)) {
            return book;
        }
        if (book.isPublished()) {
            throw ApiException.forbidden("ACCESS_DENIED", "Você não tem permissão para alterar esta obra.");
        }
        throw notFound();
    }

    private static ApiException notFound() {
        return ApiException.notFound("BOOK_NOT_FOUND", "Obra não encontrada.");
    }
}
