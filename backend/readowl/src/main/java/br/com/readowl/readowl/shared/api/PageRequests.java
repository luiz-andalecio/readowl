package br.com.readowl.readowl.shared.api;

import br.com.readowl.readowl.shared.exception.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

public final class PageRequests {

    private static final int MAX_SIZE = 50;
    private static final String DESCENDING_PREFIX = "-";

    private PageRequests() {
    }

    public static void requireValid(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw ApiException.badRequest("INVALID_PAGINATION",
                    "Use page a partir de 0 e size de 1 a " + MAX_SIZE + ".");
        }
    }

    /**
     * Para consultas que já definem a própria ordem.
     */
    public static Pageable unsorted(int page, int size) {
        requireValid(page, size);
        return PageRequest.of(page, size);
    }

    /**
     * @param sort campo da lista permitida; com "-" na frente, em ordem decrescente
     */
    public static Pageable of(int page, int size, String sort, Set<String> allowedSorts) {
        requireValid(page, size);
        boolean descending = sort.startsWith(DESCENDING_PREFIX);
        String field = descending ? sort.substring(DESCENDING_PREFIX.length()) : sort;
        if (!allowedSorts.contains(field)) {
            throw ApiException.badRequest("INVALID_SORT", "Ordenação não permitida: use " + allowedSorts + ".");
        }
        return PageRequest.of(page, size, descending ? Sort.by(field).descending() : Sort.by(field).ascending());
    }
}
