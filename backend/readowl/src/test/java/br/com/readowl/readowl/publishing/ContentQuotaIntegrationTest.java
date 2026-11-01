package br.com.readowl.readowl.publishing;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiTestConfiguration;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.RecordingMailSender;
import br.com.readowl.readowl.support.TestDatabaseConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.function.Supplier;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "readowl.limits.books-per-account=3", "readowl.limits.volumes-per-book=2",
        "readowl.limits.chapters-per-book=2", "readowl.limits.revisions-per-chapter=2",
        "readowl.limits.stored-bytes-per-account=400000"})
@ActiveProfiles("test")
@Import({TestDatabaseConfiguration.class, ApiTestConfiguration.class})
class ContentQuotaIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;
    private BookFixtures books;
    private ApiClient author;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        books = new BookFixtures(jdbc);
        mail.setFailing(false);
        String email = uniqueEmail();
        accounts.registerVerified(email);
        author = accounts.loggedIn(email, PASSWORD);
    }

    private static void assertLimit(ApiClient.Response response, String code, int limit) {
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo(code);
        assertThat(response.json().at("/limit").asInt()).isEqualTo(limit);
    }

    /**
     * Repete até o limite recusar. O pedido que recusa não pode ter sido contado como sucesso.
     */
    private static ApiClient.Response untilRefused(Supplier<ApiClient.Response> request, int maxAttempts) {
        ApiClient.Response last = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            last = request.get();
            if (last.status() == 429) {
                return last;
            }
        }
        return last;
    }

    @Test
    void anAccountHasALimitOfBooks() {
        for (int book = 0; book < 3; book++) {
            books.createBook(author);
        }

        assertLimit(author.post("/api/v1/books", books.newBook("Mais uma", BookFixtures.uniqueAcronym())), "BOOK_LIMIT_REACHED", 3);
    }

    @Test
    void aBookHasALimitOfVolumesAndChapters() {
        String book = books.createBook(author);
        String volumes = "/api/v1/books/" + book + "/volumes";
        String chapters = "/api/v1/books/" + book + "/chapters";
        assertThat(author.post(volumes, body("title", "Um")).status()).isEqualTo(201);
        assertThat(author.post(volumes, body("title", "Dois")).status()).isEqualTo(201);
        assertThat(author.post(chapters, body("title", "Um")).status()).isEqualTo(201);
        assertThat(author.post(chapters, body("title", "Dois")).status()).isEqualTo(201);

        assertLimit(author.post(volumes, body("title", "Três")), "VOLUME_LIMIT_REACHED", 2);
        assertLimit(author.post(chapters, body("title", "Três")), "CHAPTER_LIMIT_REACHED", 2);
    }

    @Test
    void aChapterHasALimitOfRevisionsButRepublishingTheSameTextDoesNotCount() {
        String chapter = books.createChapter(author, books.createBook(author), "Capítulo");
        String publish = "/api/v1/chapters/" + chapter + "/publish";
        books.publishChapter(author, chapter, "Primeira");
        books.publishChapter(author, chapter, "Segunda");

        long same = author.get("/api/v1/chapters/" + chapter + "/draft").json().at("/version").asLong();
        assertThat(author.post(publish, body("draftVersion", same)).status()).isEqualTo(200);

        long changed = books.writeDraft(author, chapter, "Terceira");
        assertLimit(author.post(publish, body("draftVersion", changed)), "REVISION_LIMIT_REACHED", 2);
    }

    @Test
    void publishingStopsAtTheStorageLimitAndTheDraftStaysSaved() {
        String chapter = books.createChapter(author, books.createBook(author), "Capítulo");
        long version = books.writeDraft(author, chapter, "a".repeat(150_000));

        ApiClient.Response refused = author.post("/api/v1/chapters/" + chapter + "/publish", body("draftVersion", version));

        assertLimit(refused, "STORAGE_LIMIT_REACHED", 400_000);
        assertThat(author.get("/api/v1/chapters/" + chapter + "/draft").json().at("/version").asLong()).isEqualTo(version);
    }

    @Test
    void aLargeDraftCountsAgainstTheStorageLimitAndASmallOneIsNotChecked() {
        String path = "/api/v1/chapters/" + books.createChapter(author, books.createBook(author), "Capítulo") + "/draft";
        long version = author.get(path).json().at("/version").asLong();

        ApiClient.Response refused = author.put(path, body("version", version, "document", BookFixtures.document("a".repeat(450_000))));
        assertLimit(refused, "STORAGE_LIMIT_REACHED", 400_000);

        assertThat(author.put(path, body("version", version, "document", BookFixtures.document("Texto curto"))).status()).isEqualTo(200);
    }

    @Test
    void creatingBooksIsLimitedPerAccount() {
        ApiClient.Response refused = untilRefused(() -> author.post("/api/v1/books", body()), 51);

        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.code()).isEqualTo("RATE_LIMITED");
        assertThat(refused.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void savingDraftsIsLimitedPerAccount() {
        String path = "/api/v1/chapters/" + books.createChapter(author, books.createBook(author), "Capítulo") + "/draft";

        assertThat(untilRefused(() -> author.put(path, body()), 121).status()).isEqualTo(429);
    }

    @Test
    void publishingIsLimitedPerAccount() {
        String path = "/api/v1/chapters/" + books.createChapter(author, books.createBook(author), "Capítulo") + "/publish";

        assertThat(untilRefused(() -> author.post(path, body()), 121).status()).isEqualTo(429);
    }

    @Test
    void structuralChangesAreLimitedPerAccount() {
        String book = books.createBook(author);

        assertThat(untilRefused(() -> author.patch("/api/v1/books/" + book, body("synopsis", "Nova sinopse")), 121).status())
                .isEqualTo(429);
    }

    @Test
    void changingTagsHasItsOwnLimitBecauseItCreatesPublicTags() {
        String book = books.createBook(author);

        ApiClient.Response refused = untilRefused(() -> author.patch("/api/v1/books/" + book, body("tags", List.of("Tag fixa"))), 31);

        assertThat(refused.status()).isEqualTo(429);
        assertThat(author.patch("/api/v1/books/" + book, body("synopsis", "Sem mexer nas tags")).status()).isEqualTo(200);
    }

    @Test
    void theLimitOfOneAccountDoesNotAffectAnother() {
        untilRefused(() -> author.post("/api/v1/books", body()), 51);
        String email = uniqueEmail();
        accounts.registerVerified(email);

        books.createBook(accounts.loggedIn(email, PASSWORD));
    }
}
