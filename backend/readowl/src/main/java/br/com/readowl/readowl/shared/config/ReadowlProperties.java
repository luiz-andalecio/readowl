package br.com.readowl.readowl.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("readowl")
public record ReadowlProperties(@NotBlank String publicUrl, @NotBlank @Email String mailFrom,
                                @Positive int maxConcurrentPasswordHashes, @NotNull @Valid Limits limits) {

    /**
     * Cotas de conteúdo. Protegem o armazenamento contra abuso.
     */
    public record Limits(@Positive int booksPerAccount, @Positive int volumesPerBook, @Positive int chaptersPerBook,
                         @Positive int revisionsPerChapter, @Positive long storedBytesPerAccount) {
    }

    /**
     * Endereço público de uma página do frontend.
     */
    public String pageUrl(String path) {
        return publicUrl.replaceAll("/+$", "") + path;
    }
}
