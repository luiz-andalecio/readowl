package br.com.readowl.readowl.publishing;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class ContentProtectionIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;
    private BookFixtures books;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        books = new BookFixtures(jdbc);
        mail.setFailing(false);
    }

    private ApiClient withRole(String role) {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("UPDATE users SET role = ? WHERE email = ?", role, email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private Map<String, Object> bookWithTag(String tag) {
        Map<String, Object> data = new java.util.LinkedHashMap<>(books.newBook("Obra", BookFixtures.uniqueAcronym()));
        data.put("tags", List.of(tag));
        return data;
    }

    private static String uniqueTag() {
        return "Tag" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void aTagOnlySuggestedOnceItIsOnAPublishedBook() {
        String tag = uniqueTag();
        ApiClient author = withRole("USER");
        ApiClient visitor = accounts.client();

        String draft = author.post("/api/v1/books", bookWithTag(tag)).json().at("/id").asString();
        assertThat(visitor.get("/api/v1/tags?query=" + tag.toLowerCase()).json().size()).isZero();

        books.publishChapter(author, books.createChapter(author, draft, "Capítulo"), "Texto");
        assertThat(author.post("/api/v1/books/" + draft + "/publish", body()).status()).isEqualTo(200);
        assertThat(visitor.get("/api/v1/tags?query=" + tag.toLowerCase()).json().at("/0/name").asString()).isEqualTo(tag);
    }

    @Test
    void anAdministratorCanRemoveATagInUseOnlyWhenAsked() {
        String tag = uniqueTag();
        ApiClient author = withRole("USER");
        String book = books.publishedBook(author, bookWithTag(tag));
        ApiClient admin = withRole("ADMIN");
        String id = author.get("/api/v1/books/" + book + "/details").json().at("/tags/0/id").asString();

        assertThat(admin.delete("/api/v1/admin/tags/" + id).code()).isEqualTo("TAG_IN_USE");

        assertThat(admin.delete("/api/v1/admin/tags/" + id + "?detach=true").status()).isEqualTo(204);

        assertThat(author.get("/api/v1/books/" + book + "/details").json().at("/tags").size()).isZero();
    }

    @Test
    void aReportKeepsTheChapterTextEvenIfTheChapterIsDeleted() {
        ApiClient author = withRole("USER");
        String book = books.publishedBook(author);
        String chapter = books.createChapter(author, book, "Capítulo denunciado");
        books.publishChapter(author, chapter, "Trecho que motivou a denúncia");
        ApiClient reporter = withRole("USER");
        String report = reporter.post("/api/v1/reports", body("targetType", "CHAPTER", "targetId", chapter, "reason", "COPYRIGHT"))
                .json().at("/id").asString();

        assertThat(author.post("/api/v1/chapters/" + chapter + "/unpublish", body()).status()).isEqualTo(200);
        assertThat(author.delete("/api/v1/chapters/" + chapter).status()).isEqualTo(204);

        assertThat(withRole("MODERATOR").get("/api/v1/moderation/reports/" + report).json().at("/targetSnapshot").asString())
                .contains("Capítulo denunciado").contains("Trecho que motivou a denúncia");
    }

    @Test
    void renamingAChapterToItsOwnTitleKeepsTheSlugAndRepeatedTitlesGetANumber() {
        ApiClient author = withRole("USER");
        String book = books.createBook(author);
        String first = books.createChapter(author, book, "Prólogo");
        String second = books.createChapter(author, book, "Prólogo");
        String third = books.createChapter(author, book, "Prólogo");

        assertThat(author.get("/api/v1/books/" + book + "/contents").json().at("/chapters/2/slug").asString()).isEqualTo("prologo-3");
        assertThat(author.patch("/api/v1/chapters/" + second, body("title", "Prólogo")).json().at("/slug").asString()).isEqualTo("prologo-2");
        assertThat(author.patch("/api/v1/chapters/" + first, body("title", "Outro")).json().at("/slug").asString()).isEqualTo("outro");
        assertThat(author.patch("/api/v1/chapters/" + third, body("title", "Prólogo!")).json().at("/slug").asString()).isEqualTo("prologo");
    }

    @Test
    void anEditWithAnOldVersionIsRefusedAndWithoutVersionStillWorks() {
        ApiClient author = withRole("USER");
        String book = books.createBook(author);
        String path = "/api/v1/books/" + book;
        long version = author.get(path + "/details").json().at("/version").asLong();

        ApiClient.Response first = author.patch(path, body("synopsis", "Primeira", "version", version));
        assertThat(first.status()).isEqualTo(200);

        ApiClient.Response stale = author.patch(path, body("synopsis", "Segunda", "version", version));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("VERSION_CONFLICT");
        assertThat(stale.json().at("/currentVersion").asLong()).isEqualTo(first.json().at("/version").asLong());
        assertThat(author.get(path + "/details").json().at("/synopsis").asString()).isEqualTo("Primeira");

        assertThat(author.patch(path, body("synopsis", "Terceira")).status()).isEqualTo(200);
    }

    @Test
    void chaptersAndVolumesAlsoRefuseOldVersions() {
        ApiClient author = withRole("USER");
        String book = books.createBook(author);
        ApiClient.Response volume = author.post("/api/v1/books/" + book + "/volumes", body("title", "Volume"));
        ApiClient.Response chapter = author.post("/api/v1/books/" + book + "/chapters", body("title", "Capítulo"));
        String volumePath = "/api/v1/volumes/" + volume.json().at("/id").asString();
        String chapterPath = "/api/v1/chapters/" + chapter.json().at("/id").asString();
        long volumeVersion = volume.json().at("/version").asLong();
        long chapterVersion = chapter.json().at("/version").asLong();

        assertThat(author.patch(volumePath, body("title", "Novo", "version", volumeVersion)).status()).isEqualTo(200);
        assertThat(author.patch(chapterPath, body("title", "Novo", "version", chapterVersion)).status()).isEqualTo(200);

        assertThat(author.patch(volumePath, body("title", "Velho", "version", volumeVersion)).code()).isEqualTo("VERSION_CONFLICT");
        assertThat(author.patch(chapterPath, body("title", "Velho", "version", chapterVersion)).code()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void theConfirmationTitleCanGoInTheBodyOrInTheUrl() {
        ApiClient author = withRole("USER");
        String inBody = books.createBook(author, "Pelo Corpo");
        String inUrl = books.createBook(author, "PelaUrl");

        assertThat(author.delete("/api/v1/books/" + inBody, body("confirmTitle", "Errado")).code()).isEqualTo("TITLE_CONFIRMATION_MISMATCH");
        assertThat(author.delete("/api/v1/books/" + inBody, body("confirmTitle", "Pelo Corpo")).status()).isEqualTo(204);
        assertThat(author.delete("/api/v1/books/" + inUrl + "?confirmTitle=PelaUrl").status()).isEqualTo(204);
    }

    @Test
    void namesThatLookLikeTheTeamAreReserved() {
        ApiClient.Response refused = accounts.client().post("/api/v1/auth/register",
                AccountFixtures.registration("Moderator", uniqueEmail(), PASSWORD));

        assertThat(refused.code()).isEqualTo("USERNAME_TAKEN");
    }
}
