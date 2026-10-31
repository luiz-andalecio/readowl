package br.com.readowl.readowl.user.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Campos ausentes não são alterados.
 */
record UpdateProfileRequest(
        @Size(max = 100) @Pattern(regexp = "(?s).*\\S.*", message = "não deve estar em branco") String name,
        @Size(max = 2000) String description,
        Boolean emailNewChapters) {
}
