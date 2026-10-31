package br.com.readowl.readowl.shared.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("readowl")
public record ReadowlProperties(@NotBlank String publicUrl, @NotBlank @Email String mailFrom) {

    /**
     * Endereço público de uma página do frontend.
     */
    public String pageUrl(String path) {
        return publicUrl.replaceAll("/+$", "") + path;
    }
}
