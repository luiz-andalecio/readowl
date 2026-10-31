package br.com.readowl.readowl.library;

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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class LibraryIntegrationTest {

    private static final String LIBRARY = "/api/v1/library";

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

    private ApiClient newUser() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private static String firstChapter(ApiClient author, String bookId) {
        return author.get("/api/v1/books/" + bookId + "/contents").json().at("/chapters/0/id").asString();
    }

    @Test
    void libraryRequiresLogin() {
        ApiClient anonymous = accounts.client();
        String anyBook = UUID.randomUUID().toString();

        assertThat(anonymous.get(LIBRARY).status()).isEqualTo(401);
        assertThat(anonymous.get(LIBRARY + "/" + anyBook).status()).isEqualTo(401);
        assertThat(anonymous.put(LIBRARY + "/" + anyBook, body()).status()).isEqualTo(401);
    }

    @Test
    void followingRequiresCsrf() {
        assertThat(newUser().postWithoutCsrf(LIBRARY + "/" + UUID.randomUUID(), body()).status()).isEqualTo(403);
    }

    @Test
    void followingIsRepeatableAndUnfollowingRemovesIt() {
        String book = books.publishedBook(newUser());
        ApiClient reader = newUser();

        assertThat(reader.get(LIBRARY + "/" + book).json().at("/following").asBoolean()).isFalse();
        assertThat(reader.put(LIBRARY + "/" + book, body()).json().at("/following").asBoolean()).isTrue();
        assertThat(reader.put(LIBRARY + "/" + book, body()).status()).isEqualTo(200);
        assertThat(reader.get(LIBRARY).json().at("/totalElements").asLong()).isEqualTo(1);

        assertThat(reader.delete(LIBRARY + "/" + book).status()).isEqualTo(204);
        assertThat(reader.delete(LIBRARY + "/" + book).status()).isEqualTo(204);
        assertThat(reader.get(LIBRARY).json().at("/totalElements").asLong()).isZero();
    }

    @Test
    void simultaneousFollowsAreNotAnError() {
        String book = books.publishedBook(newUser());
        ApiClient reader = newUser();

        List<CompletableFuture<ApiClient.Response>> requests = List.of(
                CompletableFuture.supplyAsync(() -> reader.put(LIBRARY + "/" + book, body())),
                CompletableFuture.supplyAsync(() -> reader.put(LIBRARY + "/" + book, body())),
                CompletableFuture.supplyAsync(() -> reader.put(LIBRARY + "/" + book, body())));

        assertThat(requests).allSatisfy(request -> assertThat(request.join().status()).isEqualTo(200));
    }

    @Test
    void privateBooksCannotBeFollowedAndLookLikeTheyDoNotExist() {
        String draft = books.createBook(newUser());
        ApiClient reader = newUser();

        assertThat(reader.put(LIBRARY + "/" + draft, body()).code()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(reader.get(LIBRARY + "/" + draft).code()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(reader.put(LIBRARY + "/" + UUID.randomUUID(), body()).code()).isEqualTo("BOOK_NOT_FOUND");
    }

    @Test
    void bookThatLeavesTheCatalogDisappearsFromTheListAndComesBack() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reader = newUser();
        reader.put(LIBRARY + "/" + book, body());

        author.post("/api/v1/books/" + book + "/unpublish", body());
        assertThat(reader.get(LIBRARY).json().at("/totalElements").asLong()).isZero();
        assertThat(reader.delete(LIBRARY + "/nao-e-uuid").status()).isEqualTo(400);

        author.post("/api/v1/books/" + book + "/publish", body());
        assertThat(reader.get(LIBRARY).json().at("/totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void progressIsRecordedAndTheLatestReadingWins() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String first = firstChapter(author, book);
        String second = books.createChapter(author, book, "Dois");
        books.publishChapter(author, second, "Texto");
        ApiClient reader = newUser();

        ApiClient.Response saved = reader.put(LIBRARY + "/" + book + "/progress", body("chapterId", second, "percent", 40));
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.json().at("/progress/chapterSlug").asString()).isEqualTo("dois");
        assertThat(saved.json().at("/progress/percent").asInt()).isEqualTo(40);
        assertThat(saved.json().at("/following").asBoolean()).isFalse();

        reader.put(LIBRARY + "/" + book + "/progress", body("chapterId", first, "percent", 100));
        assertThat(reader.get(LIBRARY + "/" + book).json().at("/progress/chapterId").asString()).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reading_progress WHERE book_id = ?", Integer.class,
                UUID.fromString(book))).isEqualTo(1);
    }

    @Test
    void progressAcceptsOnlyPublishedChaptersOfThatBook() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String draft = books.createChapter(author, book, "Rascunho");
        String otherBook = books.publishedBook(author);
        String foreign = firstChapter(author, otherBook);
        ApiClient reader = newUser();
        String path = LIBRARY + "/" + book + "/progress";

        assertThat(reader.put(path, body("chapterId", draft, "percent", 10)).code()).isEqualTo("CHAPTER_NOT_FOUND");
        assertThat(reader.put(path, body("chapterId", foreign, "percent", 10)).code()).isEqualTo("CHAPTER_NOT_FOUND");
        assertThat(reader.put(path, body("chapterId", UUID.randomUUID(), "percent", 10)).code()).isEqualTo("CHAPTER_NOT_FOUND");
        assertThat(reader.put(path, body("chapterId", firstChapter(author, book), "percent", 101)).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(reader.put(path, body("chapterId", firstChapter(author, book), "percent", -1)).status()).isEqualTo(400);
        assertThat(reader.put(path, body("percent", 10)).status()).isEqualTo(400);
    }

    @Test
    void progressBelongsToEachUser() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        ApiClient first = newUser();
        ApiClient second = newUser();
        first.put(LIBRARY + "/" + book + "/progress", body("chapterId", chapter, "percent", 50));
        first.put(LIBRARY + "/" + book, body());

        assertThat(second.get(LIBRARY + "/" + book).json().at("/progress").isNull()).isTrue();
        assertThat(second.get(LIBRARY + "/" + book).json().at("/following").asBoolean()).isFalse();
        assertThat(second.get(LIBRARY).json().at("/totalElements").asLong()).isZero();
    }

    @Test
    void libraryListIncludesProgressAndKeepsWorkWhenTheChapterGoesOffline() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        String other = books.createChapter(author, book, "Outro");
        books.publishChapter(author, other, "Texto");
        ApiClient reader = newUser();
        reader.put(LIBRARY + "/" + book, body());
        reader.put(LIBRARY + "/" + book + "/progress", body("chapterId", other, "percent", 75));

        ApiClient.Response list = reader.get(LIBRARY);
        assertThat(list.json().at("/content/0/book/id").asString()).isEqualTo(book);
        assertThat(list.json().at("/content/0/book/slug").asString()).isNotBlank();
        assertThat(list.json().at("/content/0/followedAt").asString()).endsWith("Z");
        assertThat(list.json().at("/content/0/progress/percent").asInt()).isEqualTo(75);

        author.post("/api/v1/chapters/" + other + "/unpublish", body());

        assertThat(reader.get(LIBRARY).json().at("/content/0/progress").isNull()).isTrue();
        assertThat(reader.get(LIBRARY + "/" + book).json().at("/progress").isNull()).isTrue();
        assertThat(chapter).isNotBlank();
    }

    @Test
    void deletingAChapterOrBookRemovesRelatedLibraryData() {
        ApiClient author = newUser();
        String title = "Obra para excluir " + UUID.randomUUID();
        String book = books.publishedBook(author, books.newBook(title, BookFixtures.uniqueAcronym()));
        String chapter = books.createChapter(author, book, "Excluível");
        books.publishChapter(author, chapter, "Texto");
        ApiClient reader = newUser();
        reader.put(LIBRARY + "/" + book, body());
        reader.put(LIBRARY + "/" + book + "/progress", body("chapterId", chapter, "percent", 10));

        author.post("/api/v1/chapters/" + chapter + "/unpublish", body());
        assertThat(author.delete("/api/v1/chapters/" + chapter).status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reading_progress WHERE book_id = ?", Integer.class,
                UUID.fromString(book))).isZero();

        author.post("/api/v1/books/" + book + "/unpublish", body());
        String confirm = URLEncoder.encode(title, StandardCharsets.UTF_8);
        assertThat(author.delete("/api/v1/books/" + book + "?confirmTitle=" + confirm).status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM book_follows WHERE book_id = ?", Integer.class,
                UUID.fromString(book))).isZero();
    }
}
