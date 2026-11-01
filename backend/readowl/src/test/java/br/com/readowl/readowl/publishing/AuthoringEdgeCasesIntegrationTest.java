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
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.registration;
import static br.com.readowl.readowl.support.AccountFixtures.unique;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Caminhos que o relatório de cobertura mostrou sem nenhum teste.
 */
@ApiIntegrationTest
class AuthoringEdgeCasesIntegrationTest {

    private static final Map<String, Object> REASON = body("reason", "Viola as regras");

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

    @Test
    void editingABookChangesItsFrequencyAndGenres() {
        ApiClient author = withRole("USER");
        String book = books.createBook(author);
        UUID other = jdbc.queryForObject("SELECT id FROM genres WHERE name <> 'Fantasia' ORDER BY name LIMIT 1", UUID.class);

        ApiClient.Response edited = author.patch("/api/v1/books/" + book,
                body("releaseFrequency", " Semanal ", "genreIds", List.of(other)));

        assertThat(edited.json().at("/releaseFrequency").asString()).isEqualTo("Semanal");
        assertThat(edited.json().at("/genres").size()).isEqualTo(1);
        assertThat(edited.json().at("/genres/0/id").asString()).isEqualTo(other.toString());
    }

    @Test
    void aRepeatedChapterInTheNewOrderIsRefused() {
        ApiClient author = withRole("USER");
        String book = books.createBook(author);
        String chapter = books.createChapter(author, book, "Capítulo");

        assertThat(author.put("/api/v1/books/" + book + "/chapters/order", body("chapterIds", List.of(chapter, chapter))).code())
                .isEqualTo("INVALID_ORDER");
    }

    @Test
    void someoneElsesVolumeOfAPublishedBookCannotBeRenamed() {
        ApiClient author = withRole("USER");
        String book = books.publishedBook(author);
        String volume = author.post("/api/v1/books/" + book + "/volumes", body("title", "Volume")).json().at("/id").asString();

        ApiClient.Response refused = withRole("USER").patch("/api/v1/volumes/" + volume, body("title", "Outro"));

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void theCatalogRefusesTooManyFilters() {
        String tags = String.join("&", IntStream.range(0, 11).mapToObj(index -> "tag=t" + index).toList());

        assertThat(accounts.client().get("/api/v1/books?" + tags).code()).isEqualTo("TOO_MANY_FILTERS");
    }

    @Test
    void hidingAChapterTwiceOrRestoringOneThatIsNotHiddenIsRefused() {
        ApiClient author = withRole("USER");
        String book = books.publishedBook(author);
        String chapter = books.createChapter(author, book, "Capítulo 2");
        books.publishChapter(author, chapter, "Texto");
        ApiClient moderator = withRole("MODERATOR");
        String path = "/api/v1/moderation/chapters/" + chapter;

        assertThat(moderator.post(path + "/restore", REASON).code()).isEqualTo("NOT_HIDDEN");
        assertThat(moderator.post(path + "/hide", REASON).status()).isEqualTo(204);
        assertThat(moderator.post(path + "/hide", REASON).code()).isEqualTo("ALREADY_HIDDEN");
    }

    @Test
    void repeatedTaxonomyNamesAreRefused() {
        ApiClient admin = withRole("ADMIN");
        String first = "Tag " + UUID.randomUUID();
        String second = "Tag " + UUID.randomUUID();
        assertThat(admin.post("/api/v1/admin/tags", body("name", first)).status()).isEqualTo(201);
        String id = admin.post("/api/v1/admin/tags", body("name", second)).json().at("/id").asString();

        assertThat(admin.post("/api/v1/admin/genres", body("name", "fantasia")).code()).isEqualTo("GENRE_NAME_TAKEN");
        assertThat(admin.post("/api/v1/admin/tags", body("name", first.toLowerCase())).code()).isEqualTo("TAG_NAME_TAKEN");
        assertThat(admin.patch("/api/v1/admin/tags/" + id, body("name", first)).code()).isEqualTo("TAG_NAME_TAKEN");
    }

    @Test
    void simultaneousReportsOfTheSamePersonBecomeOne() {
        ApiClient author = withRole("USER");
        String book = books.publishedBook(author);
        ApiClient reporter = withRole("USER");
        Map<String, Object> report = body("targetType", "BOOK", "targetId", book, "reason", "SPAM");

        List<ApiClient.Response> responses = IntStream.range(0, 6)
                .mapToObj(attempt -> CompletableFuture.supplyAsync(() -> reporter.post("/api/v1/reports", report)))
                .toList().stream().map(CompletableFuture::join).toList();

        assertThat(responses).filteredOn(response -> response.status() == 201).hasSize(1);
        assertThat(responses).filteredOn(response -> response.status() != 201)
                .allSatisfy(response -> assertThat(response.code()).isEqualTo("REPORT_ALREADY_OPEN"));
    }

    @Test
    void simultaneousRegistrationsOfTheSameUsernameCreateOneAccount() {
        String username = unique();

        List<ApiClient.Response> responses = IntStream.range(0, 6)
                .mapToObj(attempt -> CompletableFuture.supplyAsync(() -> accounts.client().post("/api/v1/auth/register",
                        registration(username, uniqueEmail(), PASSWORD))))
                .toList().stream().map(CompletableFuture::join).toList();

        assertThat(responses).filteredOn(response -> response.status() == 202).hasSize(1);
        assertThat(responses).filteredOn(response -> response.status() != 202)
                .allSatisfy(response -> assertThat(response.code()).isEqualTo("USERNAME_TAKEN"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Integer.class, username)).isEqualTo(1);
    }
}
