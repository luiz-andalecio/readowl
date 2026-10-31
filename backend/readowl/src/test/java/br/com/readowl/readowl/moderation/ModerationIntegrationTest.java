package br.com.readowl.readowl.moderation;

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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class ModerationIntegrationTest {

    private static final String REPORTS = "/api/v1/reports";
    private static final String QUEUE = "/api/v1/moderation/reports";

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

    private ApiClient withRole(String role) {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("UPDATE users SET role = ? WHERE email = ?", role, email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private static String firstChapter(ApiClient author, String book) {
        return author.get("/api/v1/books/" + book + "/contents").json().at("/chapters/0/id").asString();
    }

    private static String userId(ApiClient user) {
        return user.get("/api/v1/users/me").json().at("/id").asString();
    }

    private String comment(ApiClient user, String book, String text) {
        ApiClient.Response created = user.post("/api/v1/books/" + book + "/comments", body("content", text));
        assertThat(created.status()).isEqualTo(201);
        return created.json().at("/id").asString();
    }

    private static ApiClient.Response report(ApiClient user, String type, String target) {
        return user.post(REPORTS, body("targetType", type, "targetId", target, "reason", "SPAM"));
    }

    private static String openReport(ApiClient user, String type, String target) {
        ApiClient.Response created = report(user, type, target);
        assertThat(created.status()).isEqualTo(201);
        return created.json().at("/id").asString();
    }

    private static java.util.Map<String, Object> reason() {
        return body("reason", "Viola as regras");
    }

    @Test
    void reportingRequiresLoginAndCsrf() {
        assertThat(accounts.client().post(REPORTS, body()).status()).isEqualTo(401);
        assertThat(newUser().postWithoutCsrf(REPORTS, body()).status()).isEqualTo(403);
    }

    @Test
    void moderationRoutesAreOnlyForModeratorsAndAdministrators() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient user = newUser();
        String path = "/api/v1/moderation/books/" + book + "/hide";

        assertThat(accounts.client().get(QUEUE).status()).isEqualTo(401);
        assertThat(user.get(QUEUE).status()).isEqualTo(403);
        assertThat(user.post(path, reason()).status()).isEqualTo(403);
        assertThat(author.post(path, reason()).status()).isEqualTo(403);
        assertThat(user.get("/api/v1/moderation/comments?bookId=" + book).status()).isEqualTo(403);

        assertThat(withRole("MODERATOR").get(QUEUE).status()).isEqualTo(200);
        assertThat(withRole("ADMIN").get(QUEUE).status()).isEqualTo(200);
        assertThat(withRole("ROOT").get(QUEUE).status()).isEqualTo(200);
    }

    @Test
    void moderatorsDoNotGainAdministratorPowers() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String draftBook = books.createBook(author);
        String id = userId(author);
        ApiClient moderator = withRole("MODERATOR");

        assertThat(moderator.get("/api/v1/admin/users").status()).isEqualTo(403);
        assertThat(moderator.patch("/api/v1/admin/users/" + id, body("status", "SUSPENDED")).status()).isEqualTo(403);
        assertThat(moderator.get("/api/v1/admin/audit-log").status()).isEqualTo(403);
        assertThat(moderator.patch("/api/v1/books/" + book, body("synopsis", "Invadida")).status()).isEqualTo(403);
        assertThat(moderator.get("/api/v1/books/" + draftBook + "/details").status()).isEqualTo(404);
    }

    @Test
    void anyPublicTargetCanBeReportedAndTheQueueShowsWhatWasReported() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = firstChapter(author, book);
        ApiClient other = newUser();
        String commentId = comment(other, book, "Texto ofensivo");
        ApiClient reporter = newUser();

        ApiClient.Response created = reporter.post(REPORTS, body("targetType", "COMMENT", "targetId", commentId,
                "reason", "ABUSE", "details", "  É ofensivo  "));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().at("/status").asString()).isEqualTo("OPEN");
        assertThat(created.body()).doesNotContain("targetSnapshot").doesNotContain("reporter");
        openReport(reporter, "BOOK", book);
        openReport(reporter, "CHAPTER", chapter);
        openReport(reporter, "USER", userId(other));

        ApiClient moderator = withRole("MODERATOR");
        ApiClient.Response queue = moderator.get(QUEUE + "?targetType=COMMENT&status=OPEN");
        assertThat(queue.json().at("/totalElements").asLong()).isGreaterThanOrEqualTo(1);
        ApiClient.Response detail = moderator.get(QUEUE + "/" + created.json().at("/id").asString());
        assertThat(detail.json().at("/targetSnapshot").asString()).isEqualTo("Texto ofensivo");
        assertThat(detail.json().at("/details").asString()).isEqualTo("É ofensivo");
        assertThat(detail.json().at("/reason").asString()).isEqualTo("ABUSE");
        assertThat(detail.json().at("/targetOwner/id").asString()).isEqualTo(userId(other));
        assertThat(detail.json().at("/reporter/id").asString()).isEqualTo(userId(reporter));
        assertThat(moderator.get(QUEUE + "/" + UUID.randomUUID()).code()).isEqualTo("REPORT_NOT_FOUND");
    }

    @Test
    void theSnapshotSurvivesEditingAndDeletion() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient other = newUser();
        String commentId = comment(other, book, "Prova original");
        String id = openReport(newUser(), "COMMENT", commentId);

        other.patch("/api/v1/comments/" + commentId, body("content", "Inofensivo"));
        other.delete("/api/v1/comments/" + commentId);

        assertThat(withRole("MODERATOR").get(QUEUE + "/" + id).json().at("/targetSnapshot").asString()).isEqualTo("Prova original");
    }

    @Test
    void privateOrMissingTargetsLookLikeTheyDoNotExist() {
        ApiClient author = newUser();
        String draftBook = books.createBook(author);
        String book = books.publishedBook(author);
        String draftChapter = books.createChapter(author, book, "Rascunho");
        ApiClient other = newUser();
        String deleted = comment(other, book, "Excluído");
        other.delete("/api/v1/comments/" + deleted);
        ApiClient reporter = newUser();

        assertThat(report(reporter, "BOOK", draftBook).code()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(report(reporter, "CHAPTER", draftChapter).code()).isEqualTo("CHAPTER_NOT_FOUND");
        assertThat(report(reporter, "COMMENT", deleted).code()).isEqualTo("COMMENT_NOT_FOUND");
        assertThat(report(reporter, "USER", UUID.randomUUID().toString()).code()).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void ownContentCannotBeReported() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);

        assertThat(report(author, "BOOK", book).code()).isEqualTo("OWN_CONTENT_REPORT");
        assertThat(report(author, "CHAPTER", firstChapter(author, book)).code()).isEqualTo("OWN_CONTENT_REPORT");
        assertThat(report(author, "USER", userId(author)).code()).isEqualTo("OWN_CONTENT_REPORT");
    }

    @Test
    void invalidReportsAreRefused() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reporter = newUser();

        assertThat(reporter.post(REPORTS, body("targetType", "BOOK", "targetId", book)).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(reporter.post(REPORTS, body("targetType", "OUTRO", "targetId", book, "reason", "SPAM")).status()).isEqualTo(400);
        assertThat(reporter.post(REPORTS, body("targetType", "BOOK", "targetId", book, "reason", "SPAM",
                "details", "x".repeat(1001))).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(reporter.post(REPORTS, body("targetType", "BOOK", "targetId", book, "reason", "SPAM",
                "details", "nulo\u0000")).code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void thePersonCannotHaveTwoOpenReportsOnTheSameTarget() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reporter = newUser();
        String id = openReport(reporter, "BOOK", book);

        assertThat(report(reporter, "BOOK", book).code()).isEqualTo("REPORT_ALREADY_OPEN");
        assertThat(report(newUser(), "BOOK", book).status()).isEqualTo(201);

        withRole("MODERATOR").patch(QUEUE + "/" + id, body("status", "DISMISSED"));
        assertThat(report(reporter, "BOOK", book).status()).isEqualTo(201);
    }

    @Test
    void simultaneousReportsCreateOnlyOne() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reporter = newUser();

        List<CompletableFuture<ApiClient.Response>> requests = IntStream.range(0, 5)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> report(reporter, "BOOK", book)))
                .toList();

        List<ApiClient.Response> responses = requests.stream().map(CompletableFuture::join).toList();
        assertThat(responses.stream().filter(response -> response.status() == 201)).hasSize(1);
        assertThat(responses.stream().filter(response -> response.status() == 409))
                .hasSize(4)
                .allSatisfy(response -> assertThat(response.code()).isEqualTo("REPORT_ALREADY_OPEN"));
    }

    @Test
    void tooManyReportsAreLimitedPerAccount() {
        ApiClient author = newUser();
        ApiClient reporter = newUser();
        String book = books.publishedBook(author);

        ApiClient.Response last = null;
        for (int attempt = 0; attempt < 11; attempt++) {
            last = report(reporter, "COMMENT", UUID.randomUUID().toString());
        }

        assertThat(last.status()).isEqualTo(429);
        assertThat(last.code()).isEqualTo("RATE_LIMITED");
        assertThat(report(newUser(), "BOOK", book).status()).isEqualTo(201);
    }

    @Test
    void reportsAreClosedOnceWithTheResolution() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String id = openReport(newUser(), "BOOK", book);
        ApiClient moderator = withRole("MODERATOR");

        assertThat(moderator.patch(QUEUE + "/" + id, body("status", "OPEN")).code()).isEqualTo("INVALID_REPORT_STATUS");

        ApiClient.Response closed = moderator.patch(QUEUE + "/" + id, body("status", "RESOLVED", "note", "Conteúdo ajustado"));
        assertThat(closed.status()).isEqualTo(200);
        assertThat(closed.json().at("/status").asString()).isEqualTo("RESOLVED");
        assertThat(closed.json().at("/resolutionNote").asString()).isEqualTo("Conteúdo ajustado");
        assertThat(closed.json().at("/resolvedBy/id").asString()).isEqualTo(userId(moderator));
        assertThat(closed.json().at("/resolvedAt").asString()).endsWith("Z");

        assertThat(moderator.patch(QUEUE + "/" + id, body("status", "DISMISSED")).code()).isEqualTo("REPORT_CLOSED");
        assertThat(moderator.get(QUEUE + "?status=OPEN").body()).doesNotContain(id);
        assertThat(moderator.get(QUEUE + "?status=RESOLVED").body()).contains(id);
    }

    @Test
    void hiddenBookLeavesTheAirAndTheAuthorCannotRepublishUntilRestored() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String slug = author.get("/api/v1/books/" + book + "/details").json().at("/slug").asString();
        ApiClient moderator = withRole("MODERATOR");

        assertThat(moderator.post("/api/v1/moderation/books/" + book + "/hide", body()).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(moderator.post("/api/v1/moderation/books/" + book + "/hide", reason()).status()).isEqualTo(204);

        assertThat(accounts.client().get("/api/v1/books/" + slug).status()).isEqualTo(404);
        ApiClient.Response details = author.get("/api/v1/books/" + book + "/details");
        assertThat(details.json().at("/moderated").asBoolean()).isTrue();
        assertThat(details.json().at("/publicationState").asString()).isEqualTo("UNPUBLISHED");
        assertThat(author.post("/api/v1/books/" + book + "/publish", body()).code()).isEqualTo("BOOK_UNDER_MODERATION");
        assertThat(withRole("ADMIN").post("/api/v1/books/" + book + "/publish", body()).code()).isEqualTo("BOOK_UNDER_MODERATION");
        assertThat(moderator.post("/api/v1/moderation/books/" + book + "/hide", reason()).code()).isEqualTo("ALREADY_HIDDEN");

        assertThat(moderator.post("/api/v1/moderation/books/" + book + "/restore", reason()).status()).isEqualTo(204);
        assertThat(moderator.post("/api/v1/moderation/books/" + book + "/restore", reason()).code()).isEqualTo("NOT_HIDDEN");
        // Restaurar não republica: a decisão é do autor.
        assertThat(accounts.client().get("/api/v1/books/" + slug).status()).isEqualTo(404);
        assertThat(author.post("/api/v1/books/" + book + "/publish", body()).status()).isEqualTo(200);
        assertThat(accounts.client().get("/api/v1/books/" + slug).status()).isEqualTo(200);
    }

    @Test
    void hiddenChapterLeavesTheAirAndTheBookCannotBecomeEmpty() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String first = firstChapter(author, book);
        ApiClient moderator = withRole("MODERATOR");

        assertThat(moderator.post("/api/v1/moderation/chapters/" + first + "/hide", reason()).code()).isEqualTo("LAST_PUBLISHED_CHAPTER");

        String second = books.createChapter(author, book, "Segundo");
        books.publishChapter(author, second, "Texto");
        assertThat(moderator.post("/api/v1/moderation/chapters/" + second + "/hide", reason()).status()).isEqualTo(204);

        String slug = author.get("/api/v1/books/" + book + "/details").json().at("/slug").asString();
        assertThat(accounts.client().get("/api/v1/books/" + slug + "/chapters/segundo").status()).isEqualTo(404);
        long version = author.get("/api/v1/chapters/" + second + "/draft").json().at("/version").asLong();
        assertThat(author.post("/api/v1/chapters/" + second + "/publish", body("draftVersion", version)).code())
                .isEqualTo("CHAPTER_UNDER_MODERATION");
        assertThat(author.get("/api/v1/books/" + book + "/contents").json().at("/chapters/1/moderated").asBoolean()).isTrue();

        assertThat(moderator.post("/api/v1/moderation/chapters/" + second + "/restore", reason()).status()).isEqualTo(204);
        assertThat(author.post("/api/v1/chapters/" + second + "/publish", body("draftVersion", version)).status()).isEqualTo(200);
        assertThat(accounts.client().get("/api/v1/books/" + slug + "/chapters/segundo").status()).isEqualTo(200);
    }

    @Test
    void hiddenCommentDisappearsAndCanBeRestoredWithoutLookingEdited() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient owner = newUser();
        String id = comment(owner, book, "Moderável");
        ApiClient moderator = withRole("MODERATOR");

        assertThat(moderator.post("/api/v1/moderation/comments/" + id + "/hide", reason()).status()).isEqualTo(204);

        assertThat(accounts.client().get("/api/v1/books/" + book + "/comments").json().at("/totalElements").asLong()).isZero();
        assertThat(owner.patch("/api/v1/comments/" + id, body("content", "Voltei")).code()).isEqualTo("COMMENT_NOT_FOUND");
        assertThat(owner.delete("/api/v1/comments/" + id).code()).isEqualTo("COMMENT_NOT_FOUND");

        assertThat(moderator.post("/api/v1/moderation/comments/" + id + "/restore", reason()).status()).isEqualTo(204);
        ApiClient.Response list = accounts.client().get("/api/v1/books/" + book + "/comments");
        assertThat(list.json().at("/totalElements").asLong()).isEqualTo(1);
        assertThat(list.json().at("/content/0/edited").asBoolean()).isFalse();
    }

    @Test
    void restoringDoesNotBringBackWhatThePersonDeleted() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient owner = newUser();
        String id = comment(owner, book, "Excluí");
        owner.delete("/api/v1/comments/" + id);
        ApiClient moderator = withRole("MODERATOR");

        assertThat(moderator.post("/api/v1/moderation/comments/" + id + "/hide", reason()).code()).isEqualTo("ALREADY_HIDDEN");
        assertThat(moderator.post("/api/v1/moderation/comments/" + id + "/restore", reason()).code()).isEqualTo("NOT_HIDDEN");
        assertThat(accounts.client().get("/api/v1/books/" + book + "/comments").json().at("/totalElements").asLong()).isZero();
    }

    @Test
    void moderationSeesEveryCommentIncludingHiddenAndOutOfTheAir() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient owner = newUser();
        String visible = comment(owner, book, "Visível");
        String deleted = comment(owner, book, "Excluído pelo dono");
        owner.delete("/api/v1/comments/" + deleted);
        author.post("/api/v1/books/" + book + "/unpublish", body());
        ApiClient moderator = withRole("MODERATOR");

        ApiClient.Response onBook = moderator.get("/api/v1/moderation/comments?bookId=" + book);
        assertThat(onBook.json().at("/totalElements").asLong()).isEqualTo(2);
        assertThat(onBook.body()).contains("Excluído pelo dono").contains(visible);
        assertThat(moderator.get("/api/v1/moderation/comments?userId=" + userId(owner)).json().at("/totalElements").asLong()).isEqualTo(2);

        assertThat(moderator.get("/api/v1/moderation/comments").code()).isEqualTo("INVALID_FILTER");
        assertThat(moderator.get("/api/v1/moderation/comments?bookId=" + book + "&userId=" + userId(owner)).code()).isEqualTo("INVALID_FILTER");
    }

    @Test
    void targetsOfActionsMustExist() {
        ApiClient moderator = withRole("MODERATOR");
        String missing = UUID.randomUUID().toString();

        assertThat(moderator.post("/api/v1/moderation/books/" + missing + "/hide", reason()).code()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(moderator.post("/api/v1/moderation/chapters/" + missing + "/hide", reason()).code()).isEqualTo("CHAPTER_NOT_FOUND");
        assertThat(moderator.post("/api/v1/moderation/comments/" + missing + "/hide", reason()).code()).isEqualTo("COMMENT_NOT_FOUND");
    }
}
