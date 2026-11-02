package br.com.readowl.readowl.book.api;

import br.com.readowl.readowl.book.domain.BookStatus;
import br.com.readowl.readowl.book.domain.ContentRating;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Campos ausentes não são alterados. Com {@code version}, a versão lida, a alteração é recusada se outra pessoa
 * tiver mudado a obra depois disso.
 */
record UpdateBookRequest(
        @Size(max = 200) @Pattern(regexp = BookRules.NOT_BLANK, message = "não deve estar em branco") String title,
        @Pattern(regexp = BookRules.ACRONYM, message = BookRules.ACRONYM_MESSAGE) String acronym,
        @Size(max = 2000) @Pattern(regexp = BookRules.NOT_BLANK, message = "não deve estar em branco") String synopsis,
        @Size(max = 50) String releaseFrequency,
        BookStatus status,
        ContentRating contentRating,
        @Size(max = 1000) String coverUrl,
        @Size(min = 1, max = 5) List<@NotNull UUID> genreIds,
        @Size(max = 10)
        List<@NotBlank @Size(max = 50) @Pattern(regexp = BookRules.TAG, message = BookRules.TAG_MESSAGE) String> tags,
        Long version) {
}
