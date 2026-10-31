package br.com.readowl.readowl.book.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

record CreateBookRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Pattern(regexp = BookRules.ACRONYM, message = BookRules.ACRONYM_MESSAGE) String acronym,
        @NotBlank @Size(max = 2000) String synopsis,
        @Size(max = 50) String releaseFrequency,
        @NotNull @Size(min = 1, max = 5) List<@NotNull UUID> genreIds,
        @Size(max = 10)
        List<@NotBlank @Size(max = 50) @Pattern(regexp = BookRules.TAG, message = BookRules.TAG_MESSAGE) String> tags) {
}
