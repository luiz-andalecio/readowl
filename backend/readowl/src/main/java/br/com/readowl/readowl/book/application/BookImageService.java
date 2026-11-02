package br.com.readowl.readowl.book.application;

import br.com.readowl.readowl.book.domain.Book;
import br.com.readowl.readowl.book.infrastructure.BookRepository;
import br.com.readowl.readowl.media.application.StoredImageType;
import br.com.readowl.readowl.media.application.StoredImages;
import br.com.readowl.readowl.user.application.SessionUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.UUID;

/**
 * Capa e banner da obra. A permissão é conferida antes do download e do envio, que ficam fora de transação, e de novo ao
 * salvar. Se salvar falhar, o arquivo novo é apagado.
 */
@Service
public class BookImageService {

    private final BookAccess access;
    private final BookRepository books;
    private final StoredImages images;
    private final TransactionOperations transaction;

    BookImageService(BookAccess access, BookRepository books, StoredImages images, TransactionOperations transaction) {
        this.access = access;
        this.books = books;
        this.images = images;
        this.transaction = transaction;
    }

    public BookDetails replaceWithFile(UUID bookId, StoredImageType type, byte[] file, SessionUser principal) {
        requireManageable(bookId, principal);
        return attach(bookId, type, images.fromFile(type, bookId, file), principal);
    }

    public BookDetails replaceWithLink(UUID bookId, StoredImageType type, String url, SessionUser principal) {
        requireManageable(bookId, principal);
        return attach(bookId, type, images.fromLink(type, bookId, url), principal);
    }

    public void remove(UUID bookId, StoredImageType type, SessionUser principal) {
        transaction.executeWithoutResult(status -> clear(access.requireManageable(bookId, principal), type));
    }

    /**
     * Junta-se à transação de quem chama, como a moderação.
     */
    public void clear(Book book, StoredImageType type) {
        change(book, type, null, null);
    }

    private void requireManageable(UUID bookId, SessionUser principal) {
        transaction.executeWithoutResult(status -> access.requireManageable(bookId, principal));
    }

    private BookDetails attach(UUID bookId, StoredImageType type, StoredImages.Stored stored, SessionUser principal) {
        try {
            return transaction.execute(status -> {
                Book book = access.requireManageable(bookId, principal);
                change(book, type, stored.key(), stored.url());
                return BookDetails.from(books.saveAndFlush(book));
            });
        } catch (RuntimeException ex) {
            images.release(stored.key());
            throw ex;
        }
    }

    private void change(Book book, StoredImageType type, String key, String url) {
        if (type == StoredImageType.COVER) {
            images.release(book.getCoverKey());
            book.changeCover(key, url);
        } else {
            images.release(book.getBannerKey());
            book.changeBanner(key, url);
        }
    }
}
