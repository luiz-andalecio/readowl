package br.com.readowl.readowl.community;

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
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class ChapterViewIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionOperations transaction;

    private AccountFixtures accounts;
    private BookFixtures books;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        books = new BookFixtures(jdbc);
        mail.setFailing(false);
    }

    private ApiClient newUser() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private static String views(String chapterId) {
        return "/api/v1/chapters/" + chapterId + "/views";
    }

    private static String firstChapter(ApiClient author, String bookId) {
        return author.get("/api/v1/books/" + bookId + "/contents").json().at("/chapters/0/id").asString();
    }

    private long chapterViews(String chapterId) {
        return jdbc.queryForObject("SELECT view_count FROM chapters WHERE id = ?", Long.class, UUID.fromString(chapterId));
    }

    private long bookViews(String bookId) {
        return jdbc.queryForObject("SELECT view_count FROM books WHERE id = ?", Long.class, UUID.fromString(bookId));
    }

    private void expireWindow(String chapterId) {
        jdbc.update("UPDATE chapter_views SET last_counted_at = last_counted_at - interval '2 minutes' WHERE chapter_id = ?",
                UUID.fromString(chapterId));
    }

    @Test
    void anonymousVisitsAreNotCountedAndCsrfIsRequired() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);

        assertThat(accounts.client().post(views(chapter), body()).status()).isEqualTo(401);
        assertThat(newUser().postWithoutCsrf(views(chapter), body()).status()).isEqualTo(403);
        assertThat(chapterViews(chapter)).isZero();
    }

    @Test
    void readingDoesNotCountOnlyTheExplicitEventDoes() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        String slug = author.get("/api/v1/books/" + book + "/details").json().at("/slug").asString();
        ApiClient reader = newUser();

        assertThat(reader.get("/api/v1/books/" + slug + "/chapters/capitulo-1").status()).isEqualTo(200);
        assertThat(chapterViews(chapter)).isZero();

        assertThat(reader.post(views(chapter), body()).status()).isEqualTo(204);
        assertThat(chapterViews(chapter)).isEqualTo(1);
        assertThat(bookViews(book)).isEqualTo(1);
        assertThat(accounts.client().get("/api/v1/books/" + slug).json().at("/viewCount").asLong()).isEqualTo(1);
    }

    @Test
    void theAuthorsOwnVisitsDoNotCount() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);

        assertThat(author.post(views(chapter), body()).status()).isEqualTo(204);

        assertThat(chapterViews(chapter)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chapter_views WHERE chapter_id = ?", Integer.class,
                UUID.fromString(chapter))).isZero();
    }

    @Test
    void aUserCountsOnceEveryTwoMinutesPerChapter() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        ApiClient reader = newUser();

        reader.post(views(chapter), body());
        assertThat(reader.post(views(chapter), body()).status()).isEqualTo(204);
        assertThat(chapterViews(chapter)).isEqualTo(1);

        // Ainda dentro da janela.
        jdbc.update("UPDATE chapter_views SET last_counted_at = now() - interval '1 minute 50 seconds' WHERE chapter_id = ?",
                UUID.fromString(chapter));
        reader.post(views(chapter), body());
        assertThat(chapterViews(chapter)).isEqualTo(1);

        expireWindow(chapter);
        reader.post(views(chapter), body());
        assertThat(chapterViews(chapter)).isEqualTo(2);
        assertThat(bookViews(book)).isEqualTo(2);
    }

    @Test
    void differentUsersAndChaptersCountSeparately() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String first = firstChapter(author, book);
        String second = books.createChapter(author, book, "Dois");
        books.publishChapter(author, second, "Texto");
        ApiClient alice = newUser();
        ApiClient bruno = newUser();

        alice.post(views(first), body());
        alice.post(views(second), body());
        bruno.post(views(first), body());

        assertThat(chapterViews(first)).isEqualTo(2);
        assertThat(chapterViews(second)).isEqualTo(1);
        assertThat(bookViews(book)).isEqualTo(3);
    }

    @Test
    void simultaneousVisitsOfTheSameUserCountOnce() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        ApiClient reader = newUser();

        List<CompletableFuture<ApiClient.Response>> requests = IntStream.range(0, 20)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> reader.post(views(chapter), body())))
                .toList();

        assertThat(requests).allSatisfy(request -> assertThat(request.join().status()).isEqualTo(204));
        assertThat(chapterViews(chapter)).isEqualTo(1);
        assertThat(bookViews(book)).isEqualTo(1);
    }

    @Test
    void simultaneousVisitsAfterTheWindowAlsoCountOnce() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        ApiClient reader = newUser();
        reader.post(views(chapter), body());
        expireWindow(chapter);

        List<CompletableFuture<ApiClient.Response>> requests = IntStream.range(0, 20)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> reader.post(views(chapter), body())))
                .toList();

        assertThat(requests).allSatisfy(request -> assertThat(request.join().status()).isEqualTo(204));
        assertThat(chapterViews(chapter)).isEqualTo(2);
    }

    @Test
    void simultaneousUsersAreAllCounted() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        List<ApiClient> readers = IntStream.range(0, 8).mapToObj(i -> newUser()).toList();

        List<CompletableFuture<ApiClient.Response>> requests = readers.stream()
                .map(reader -> CompletableFuture.supplyAsync(() -> reader.post(views(chapter), body())))
                .toList();

        assertThat(requests).allSatisfy(request -> assertThat(request.join().status()).isEqualTo(204));
        assertThat(chapterViews(chapter)).isEqualTo(8);
        assertThat(bookViews(book)).isEqualTo(8);
    }

    @Test
    void authorEditsDuringVisitsNeitherFailNorLoseCounts() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        List<ApiClient> readers = IntStream.range(0, 8).mapToObj(i -> newUser()).toList();

        List<CompletableFuture<ApiClient.Response>> visits = readers.stream()
                .map(reader -> CompletableFuture.supplyAsync(() -> reader.post(views(chapter), body())))
                .toList();

        // Uma edição por vez: o que se verifica aqui é a convivência com as visitas, sem 409 nem deadlock.
        List<ApiClient.Response> edits = new ArrayList<>();
        for (int i = 0; i < readers.size(); i++) {
            edits.add(author.patch("/api/v1/chapters/" + chapter, body("title", "Título " + i)));
            edits.add(author.patch("/api/v1/books/" + book, body("synopsis", "Sinopse " + i)));
        }

        assertThat(visits).allSatisfy(visit -> assertThat(visit.join().status()).isEqualTo(204));
        assertThat(edits).allSatisfy(edit -> assertThat(edit.status()).isEqualTo(200));
        assertThat(chapterViews(chapter)).isEqualTo(8);
        assertThat(bookViews(book)).isEqualTo(8);
    }

    /**
     * Reproduz a ordem da publicação: bloqueia a obra e só depois altera o capítulo. Se a visita alterasse o
     * capítulo antes da obra, as duas transações ficariam esperando uma pela outra.
     */
    @Test
    void visitDuringPublicationWaitsInsteadOfDeadlocking() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        ApiClient reader = newUser();
        reader.get("/api/v1/auth/csrf");

        CompletableFuture<ApiClient.Response> visit = transaction.execute(status -> {
            jdbc.queryForObject("SELECT id FROM books WHERE id = ? FOR UPDATE", UUID.class, UUID.fromString(book));
            CompletableFuture<ApiClient.Response> started =
                    CompletableFuture.supplyAsync(() -> reader.post(views(chapter), body()));
            // Em outra conexão: dentro de uma transação, pg_stat_activity devolve sempre a primeira leitura.
            CompletableFuture.runAsync(this::awaitBlockedVisit).join();
            jdbc.update("UPDATE chapters SET display_order = display_order WHERE id = ?", UUID.fromString(chapter));
            return started;
        });

        assertThat(visit.join().status()).isEqualTo(204);
        assertThat(chapterViews(chapter)).isEqualTo(1);
        assertThat(bookViews(book)).isEqualTo(1);
    }

    private void awaitBlockedVisit() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query LIKE '%INSERT INTO chapter_views%'
                    """, Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("A visita não chegou a esperar pelo bloqueio da obra");
    }

    @Test
    void contentOutOfTheAirIsNotCounted() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        String draft = books.createChapter(author, book, "Rascunho");
        ApiClient reader = newUser();

        assertThat(reader.post(views(draft), body()).code()).isEqualTo("CHAPTER_NOT_FOUND");
        assertThat(reader.post(views(UUID.randomUUID().toString()), body()).code()).isEqualTo("CHAPTER_NOT_FOUND");

        author.post("/api/v1/books/" + book + "/unpublish", body());
        assertThat(reader.post(views(chapter), body()).code()).isEqualTo("CHAPTER_NOT_FOUND");
        assertThat(bookViews(book)).isZero();
    }

    @Test
    void chapterCountsAreOnlyVisibleToTheAuthorAndAdministrators() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        ApiClient reader = newUser();
        reader.post(views(chapter), body());

        assertThat(author.get("/api/v1/books/" + book + "/contents").json().at("/chapters/0/viewCount").asLong()).isEqualTo(1);

        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);
        assertThat(accounts.loggedIn(email, PASSWORD).get("/api/v1/books/" + book + "/contents").json()
                .at("/chapters/0/viewCount").asLong()).isEqualTo(1);

        assertThat(reader.get("/api/v1/books/" + book + "/contents").status()).isEqualTo(403);
        String slug = author.get("/api/v1/books/" + book + "/details").json().at("/slug").asString();
        assertThat(reader.get("/api/v1/books/" + slug).json().at("/chapters/0").has("viewCount")).isFalse();
        assertThat(reader.get("/api/v1/books/" + slug + "/chapters/capitulo-1").body()).doesNotContain("viewCount");
    }

    @Test
    void theClientCannotChooseWhatIsCounted() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        ApiClient reader = newUser();

        reader.post(views(chapter), body("count", 1000, "viewCount", 1000, "userId", UUID.randomUUID(), "bookId", UUID.randomUUID()));

        assertThat(chapterViews(chapter)).isEqualTo(1);
        assertThat(bookViews(book)).isEqualTo(1);
    }

    @Test
    void tooManyVisitsAreLimitedPerAccount() {
        ApiClient author = newUser();
        String chapter = firstChapter(author, books.publishedBook(author));
        ApiClient reader = newUser();

        // A janela é fixa: se virar durante o teste, uma das duas ainda passa do limite de 120.
        ApiClient.Response last = null;
        for (int attempt = 0; attempt < 241; attempt++) {
            last = reader.post(views(chapter), body());
            if (last.status() == 429) {
                break;
            }
        }

        assertThat(last.status()).isEqualTo(429);
        assertThat(last.code()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void keepsNoIpAddressInTheViewRecord() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'chapter_views'", String.class);

        assertThat(columns).containsExactlyInAnyOrder("user_id", "chapter_id", "last_counted_at");
    }
}
