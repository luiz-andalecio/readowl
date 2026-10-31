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
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class RatingAndReactionIntegrationTest {

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

    private static String rating(String bookId) {
        return "/api/v1/books/" + bookId + "/rating";
    }

    private static String reactions(String chapterId) {
        return "/api/v1/chapters/" + chapterId + "/reactions";
    }

    private static String firstChapter(ApiClient author, String bookId) {
        return author.get("/api/v1/books/" + bookId + "/contents").json().at("/chapters/0/id").asString();
    }

    @Test
    void summariesArePublicButPersonalStateRequiresLogin() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        ApiClient anonymous = accounts.client();

        ApiClient.Response summary = anonymous.get(rating(book));
        assertThat(summary.status()).isEqualTo(200);
        assertThat(summary.json().at("/average").isNull()).isTrue();
        assertThat(summary.json().at("/count").asLong()).isZero();
        assertThat(summary.setCookie("SESSION")).isEmpty();

        ApiClient.Response counts = anonymous.get(reactions(chapter));
        assertThat(counts.status()).isEqualTo(200);
        assertThat(counts.json().at("/total").asLong()).isZero();
        assertThat(counts.json().at("/counts").propertyNames())
                .containsExactlyInAnyOrder("LIKE", "LOVE", "LAUGH", "WOW", "SAD", "ANGRY");
        assertThat(counts.json().at("/counts/LOVE").asLong()).isZero();

        assertThat(anonymous.get(rating(book) + "/me").status()).isEqualTo(401);
        assertThat(anonymous.put(rating(book) + "/me", body("score", 5)).status()).isEqualTo(401);
        assertThat(anonymous.delete(rating(book) + "/me").status()).isEqualTo(401);
        assertThat(anonymous.get(reactions(chapter) + "/me").status()).isEqualTo(401);
        assertThat(anonymous.put(reactions(chapter) + "/me", body("type", "LIKE")).status()).isEqualTo(401);
    }

    @Test
    void writingRequiresCsrf() {
        ApiClient reader = newUser();
        assertThat(reader.postWithoutCsrf(rating(UUID.randomUUID().toString()) + "/me", body()).status()).isEqualTo(403);
    }

    @Test
    void eachUserHasOneRatingAndTheAverageComesFromTheStoredScores() {
        String book = books.publishedBook(newUser());
        ApiClient first = newUser();
        ApiClient second = newUser();
        ApiClient third = newUser();

        ApiClient.Response rated = first.put(rating(book) + "/me", body("score", 5));
        assertThat(rated.status()).isEqualTo(200);
        assertThat(rated.json().at("/score").asInt()).isEqualTo(5);
        assertThat(rated.json().at("/summary/count").asLong()).isEqualTo(1);

        first.put(rating(book) + "/me", body("score", 4));
        second.put(rating(book) + "/me", body("score", 2));
        third.put(rating(book) + "/me", body("score", 5));

        JsonNode summary = accounts.client().get(rating(book)).json();
        assertThat(summary.at("/count").asLong()).isEqualTo(3);
        assertThat(summary.at("/average").decimalValue()).isEqualByComparingTo("3.67");
        assertThat(first.get(rating(book) + "/me").json().at("/score").asInt()).isEqualTo(4);
        assertThat(newUser().get(rating(book) + "/me").json().at("/score").isNull()).isTrue();
    }

    @Test
    void clientCannotForgeTheAverageOrAnotherUsersRating() {
        String book = books.publishedBook(newUser());
        ApiClient victim = newUser();
        victim.put(rating(book) + "/me", body("score", 1));
        ApiClient attacker = newUser();

        ApiClient.Response response = attacker.put(rating(book) + "/me",
                body("score", 5, "average", 5.0, "count", 1000, "userId", UUID.randomUUID()));

        assertThat(response.json().at("/summary/count").asLong()).isEqualTo(2);
        assertThat(response.json().at("/summary/average").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(victim.get(rating(book) + "/me").json().at("/score").asInt()).isEqualTo(1);
    }

    @Test
    void scoreMustBeBetweenOneAndFive() {
        String book = books.publishedBook(newUser());
        ApiClient reader = newUser();

        assertThat(reader.put(rating(book) + "/me", body("score", 0)).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(reader.put(rating(book) + "/me", body("score", 6)).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(reader.put(rating(book) + "/me", body()).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(reader.put(rating(book) + "/me", body("score", "abc")).status()).isEqualTo(400);
    }

    @Test
    void authorCannotRateOrReactToOwnWorkButAdministratorsAndOthersCan() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);

        assertThat(author.put(rating(book) + "/me", body("score", 5)).code()).isEqualTo("OWN_BOOK_RATING");
        assertThat(author.put(reactions(chapter) + "/me", body("type", "LOVE")).code()).isEqualTo("OWN_CHAPTER_REACTION");
        assertThat(accounts.client().get(rating(book)).json().at("/count").asLong()).isZero();

        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);
        assertThat(accounts.loggedIn(email, PASSWORD).put(rating(book) + "/me", body("score", 3)).status()).isEqualTo(200);
    }

    @Test
    void privateContentCannotBeRatedOrReactedToAndLooksMissing() {
        ApiClient author = newUser();
        String draft = books.createBook(author);
        String published = books.publishedBook(author);
        String draftChapter = books.createChapter(author, published, "Rascunho");
        ApiClient reader = newUser();

        assertThat(reader.put(rating(draft) + "/me", body("score", 5)).code()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(reader.get(rating(draft)).code()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(reader.get(rating(draft) + "/me").code()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(reader.put(reactions(draftChapter) + "/me", body("type", "LIKE")).code()).isEqualTo("CHAPTER_NOT_FOUND");
        assertThat(reader.get(reactions(draftChapter)).code()).isEqualTo("CHAPTER_NOT_FOUND");
        assertThat(reader.get(reactions(draftChapter) + "/me").code()).isEqualTo("CHAPTER_NOT_FOUND");
    }

    @Test
    void removingIsRepeatableEvenWithTheWorkOutOfTheAir() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        ApiClient reader = newUser();
        reader.put(rating(book) + "/me", body("score", 5));
        reader.put(reactions(chapter) + "/me", body("type", "WOW"));

        author.post("/api/v1/books/" + book + "/unpublish", body());

        assertThat(reader.delete(rating(book) + "/me").status()).isEqualTo(204);
        assertThat(reader.delete(rating(book) + "/me").status()).isEqualTo(204);
        assertThat(reader.delete(reactions(chapter) + "/me").status()).isEqualTo(204);
        assertThat(reader.delete(reactions(chapter) + "/me").status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM book_ratings WHERE book_id = ?", Integer.class,
                UUID.fromString(book))).isZero();
    }

    @Test
    void simultaneousRatingsKeepOneRowForEachUser() {
        String book = books.publishedBook(newUser());
        ApiClient reader = newUser();

        List<CompletableFuture<ApiClient.Response>> requests = IntStream.rangeClosed(1, 5)
                .mapToObj(score -> CompletableFuture.supplyAsync(() -> reader.put(rating(book) + "/me", body("score", score))))
                .toList();

        assertThat(requests).allSatisfy(request -> assertThat(request.join().status()).isEqualTo(200));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM book_ratings WHERE book_id = ?", Integer.class,
                UUID.fromString(book))).isEqualTo(1);
    }

    @Test
    void simultaneousRemovalsAreNotAnError() {
        String book = books.publishedBook(newUser());
        ApiClient reader = newUser();
        reader.put(rating(book) + "/me", body("score", 5));

        List<CompletableFuture<ApiClient.Response>> requests = IntStream.range(0, 3)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> reader.delete(rating(book) + "/me")))
                .toList();

        assertThat(requests).allSatisfy(request -> assertThat(request.join().status()).isEqualTo(204));
    }

    @Test
    void reactingAgainReplacesTheReaction() {
        ApiClient author = newUser();
        String chapter = firstChapter(author, books.publishedBook(author));
        ApiClient first = newUser();
        ApiClient second = newUser();

        ApiClient.Response reacted = first.put(reactions(chapter) + "/me", body("type", "LIKE"));
        assertThat(reacted.status()).isEqualTo(200);
        assertThat(reacted.json().at("/type").asString()).isEqualTo("LIKE");
        assertThat(reacted.json().at("/summary/counts/LIKE").asLong()).isEqualTo(1);

        second.put(reactions(chapter) + "/me", body("type", "LIKE"));
        first.put(reactions(chapter) + "/me", body("type", "SAD"));

        JsonNode summary = accounts.client().get(reactions(chapter)).json();
        assertThat(summary.at("/total").asLong()).isEqualTo(2);
        assertThat(summary.at("/counts/LIKE").asLong()).isEqualTo(1);
        assertThat(summary.at("/counts/SAD").asLong()).isEqualTo(1);
        assertThat(first.get(reactions(chapter) + "/me").json().at("/type").asString()).isEqualTo("SAD");
        assertThat(newUser().get(reactions(chapter) + "/me").json().at("/type").isNull()).isTrue();

        assertThat(first.delete(reactions(chapter) + "/me").status()).isEqualTo(204);
        assertThat(accounts.client().get(reactions(chapter)).json().at("/total").asLong()).isEqualTo(1);
    }

    @Test
    void reactionTypeMustBeKnown() {
        ApiClient author = newUser();
        String chapter = firstChapter(author, books.publishedBook(author));
        ApiClient reader = newUser();

        assertThat(reader.put(reactions(chapter) + "/me", body("type", "HATE")).status()).isEqualTo(400);
        assertThat(reader.put(reactions(chapter) + "/me", body()).code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void suspendedAccountsStopCounting() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient reader = accounts.loggedIn(email, PASSWORD);
        reader.put(rating(book) + "/me", body("score", 1));
        reader.put(reactions(chapter) + "/me", body("type", "ANGRY"));

        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email);

        assertThat(accounts.client().get(rating(book)).json().at("/count").asLong()).isZero();
        assertThat(accounts.client().get(reactions(chapter)).json().at("/total").asLong()).isZero();

        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE email = ?", email);
        assertThat(accounts.client().get(rating(book)).json().at("/count").asLong()).isEqualTo(1);
    }

    @Test
    void tooManyInteractionsAreLimitedPerAccount() {
        String book = books.publishedBook(newUser());
        ApiClient reader = newUser();

        // A janela é fixa: se virar durante o teste, uma das duas ainda passa do limite de 60.
        ApiClient.Response last = null;
        for (int attempt = 0; attempt < 121; attempt++) {
            last = reader.put(rating(book) + "/me", body("score", attempt % 5 + 1));
            if (last.status() == 429) {
                break;
            }
        }

        assertThat(last.status()).isEqualTo(429);
        assertThat(last.code()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void deletingAChapterRemovesItsReactions() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = books.createChapter(author, book, "Excluível");
        books.publishChapter(author, chapter, "Texto");
        newUser().put(reactions(chapter) + "/me", body("type", "LIKE"));

        author.post("/api/v1/chapters/" + chapter + "/unpublish", body());
        assertThat(author.delete("/api/v1/chapters/" + chapter).status()).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chapter_reactions WHERE chapter_id = ?", Integer.class,
                UUID.fromString(chapter))).isZero();
    }
}
