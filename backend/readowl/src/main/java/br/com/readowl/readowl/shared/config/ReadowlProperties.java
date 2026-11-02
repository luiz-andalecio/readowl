package br.com.readowl.readowl.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties("readowl")
public record ReadowlProperties(@NotBlank String publicUrl, @NotBlank @Email String mailFrom,
                                @Positive int maxConcurrentPasswordHashes, @NotNull @Valid Limits limits,
                                @NotNull @Valid Sessions sessions, @NotNull @Valid Terms terms,
                                @NotNull @Valid Retention retention, @NotNull Storage storage, @NotNull @Valid Images images,
                                boolean privilegedGoogleLoginRequired) {

    /**
     * Cotas de conteúdo. Protegem o armazenamento contra abuso.
     */
    public record Limits(@Positive int booksPerAccount, @Positive int volumesPerBook, @Positive int chaptersPerBook,
                         @Positive int revisionsPerChapter, @Positive long storedBytesPerAccount) {
    }

    /**
     * Inatividade permitida e duração máxima, que vale mesmo com uso contínuo. Contas com papel de moderação ou
     * administração têm prazos menores, e "manter conectado" não vale para elas.
     */
    public record Sessions(@NotNull Duration rememberedIdle, @NotNull Duration maxAge, @NotNull Duration privilegedIdle,
                           @NotNull Duration privilegedMaxAge, @NotNull Duration recentLogin) {
    }

    /**
     * @param version versão vigente: quem aceitou outra precisa aceitar de novo
     */
    public record Terms(@NotBlank @Size(max = 20) String version, @Positive int minimumAge) {
    }

    /**
     * Quanto cada dado fica guardado. Ver docs/personal-data.md.
     */
    public record Retention(@NotNull Duration unverifiedAccounts, @NotNull Duration notifications,
                            @NotNull Duration deletedCommentText, @NotNull Duration closedReports,
                            @NotNull Duration auditLog, @NotNull Duration accessRecords) {
    }

    /**
     * @param allowedHosts domínios de onde podem vir as imagens hospedadas em outros sites
     */
    public record Images(@NotEmpty List<String> allowedHosts) {
    }

    /**
     * Armazenamento compatível com S3. Vazio, as fotos de perfil ficam indisponíveis.
     */
    public record Storage(String endpoint, String region, String bucket, String accessKey, String secretKey, String publicUrl) {

        // Sem as credenciais, se isto for parar em um log.
        @Override
        public String toString() {
            return "Storage[endpoint=" + endpoint + ", bucket=" + bucket + "]";
        }
    }

    /**
     * Endereço público de uma página do frontend.
     */
    public String pageUrl(String path) {
        return publicUrl.replaceAll("/+$", "") + path;
    }
}
