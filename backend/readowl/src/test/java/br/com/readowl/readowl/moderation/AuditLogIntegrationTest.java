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
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class AuditLogIntegrationTest {

    private static final String AUDIT = "/api/v1/admin/audit-log";

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

    private static String userId(ApiClient user) {
        return user.get("/api/v1/users/me").json().at("/id").asString();
    }

    private long entries(String actorId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ?", Long.class, UUID.fromString(actorId));
    }

    @Test
    void onlyAdministratorsReadTheLog() {
        assertThat(accounts.client().get(AUDIT).status()).isEqualTo(401);
        assertThat(newUser().get(AUDIT).status()).isEqualTo(403);
        assertThat(withRole("MODERATOR").get(AUDIT).status()).isEqualTo(403);
        assertThat(withRole("ADMIN").get(AUDIT).status()).isEqualTo(200);
        assertThat(withRole("ROOT").get(AUDIT).status()).isEqualTo(200);
    }

    @Test
    void userChangesRecordActorBeforeAndAfterWithoutFreeText() {
        ApiClient root = withRole("ROOT");
        ApiClient target = newUser();
        String targetId = userId(target);

        ApiClient.Response updated = root.patch("/api/v1/admin/users/" + targetId,
                body("role", "MODERATOR", "description", "Texto privado da pessoa"));
        assertThat(updated.status()).isEqualTo(200);

        JsonNode entry = root.get(AUDIT + "?resource=users/" + targetId).json().at("/content/0");
        assertThat(entry.at("/action").asString()).isEqualTo("USER_UPDATED");
        assertThat(entry.at("/outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.at("/actorId").asString()).isEqualTo(userId(root));
        assertThat(entry.at("/actorUsername").asString()).isNotBlank();
        assertThat(entry.at("/details/role/from").asString()).isEqualTo("USER");
        assertThat(entry.at("/details/role/to").asString()).isEqualTo("MODERATOR");
        assertThat(entry.at("/details/description").asString()).isEqualTo("alterada");
        assertThat(entry.at("/correlationId").asString()).isEqualTo(updated.headers().firstValue("X-Correlation-Id").orElseThrow());
        assertThat(entry.at("/createdAt").asString()).endsWith("Z");
        assertThat(entry.toString()).doesNotContain("Texto privado");
    }

    @Test
    void failedSensitiveActionsAreRecordedAsFailuresWithoutChangingAnything() {
        ApiClient root = withRole("ROOT");
        String id = userId(root);
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE role = 'ROOT' AND id <> ?", UUID.fromString(id));

        ApiClient.Response refused = root.patch("/api/v1/admin/users/" + id, body("role", "USER"));
        assertThat(refused.code()).isEqualTo("LAST_ROOT");

        JsonNode entry = root.get(AUDIT + "?actorId=" + id).json().at("/content/0");
        assertThat(entry.at("/outcome").asString()).isEqualTo("FAILED");
        assertThat(entry.at("/action").asString()).isEqualTo("PATCH /api/v1/admin/users/{id}");
        assertThat(entry.at("/resource").asString()).isEqualTo("/api/v1/admin/users/" + id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'USER_UPDATED' AND resource = ?",
                Long.class, "users/" + id)).isZero();
    }

    @Test
    void moderationActionsRecordTheReasonOnce() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient moderator = withRole("MODERATOR");
        String moderatorId = userId(moderator);

        moderator.post("/api/v1/moderation/books/" + book + "/hide", body("reason", "Plágio confirmado"));

        ApiClient root = withRole("ROOT");
        JsonNode list = root.get(AUDIT + "?actorId=" + moderatorId).json();
        assertThat(list.at("/totalElements").asLong()).isEqualTo(1);
        assertThat(list.at("/content/0/action").asString()).isEqualTo("BOOK_HIDDEN");
        assertThat(list.at("/content/0/resource").asString()).isEqualTo("books/" + book);
        assertThat(list.at("/content/0/details/reason").asString()).isEqualTo("Plágio confirmado");

        moderator.post("/api/v1/moderation/books/" + book + "/hide", body("reason", "De novo"));
        JsonNode failed = root.get(AUDIT + "?actorId=" + moderatorId).json().at("/content/0");
        assertThat(failed.at("/outcome").asString()).isEqualTo("FAILED");
    }

    @Test
    void closingAReportIsRecorded() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String report = newUser().post("/api/v1/reports", body("targetType", "BOOK", "targetId", book, "reason", "SPAM"))
                .json().at("/id").asString();
        ApiClient moderator = withRole("MODERATOR");

        moderator.patch("/api/v1/moderation/reports/" + report, body("status", "DISMISSED", "note", "Sem fundamento"));

        JsonNode entry = withRole("ROOT").get(AUDIT + "?resource=reports/" + report).json().at("/content/0");
        assertThat(entry.at("/action").asString()).isEqualTo("REPORT_CLOSED");
        assertThat(entry.at("/details/status").asString()).isEqualTo("DISMISSED");
    }

    @Test
    void administratorsActingOnOtherPeoplesContentAreRecorded() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = author.get("/api/v1/books/" + book + "/contents").json().at("/chapters/0/id").asString();
        String comment = newUser().post("/api/v1/books/" + book + "/comments", body("content", "Oi"))
                .json().at("/id").asString();
        ApiClient admin = withRole("ADMIN");
        String adminId = userId(admin);

        assertThat(admin.patch("/api/v1/books/" + book, body("synopsis", "Texto privado da sinopse")).status()).isEqualTo(200);
        assertThat(admin.patch("/api/v1/chapters/" + chapter, body("title", "Novo título")).status()).isEqualTo(200);
        assertThat(admin.patch("/api/v1/comments/" + comment, body("content", "Moderado")).status()).isEqualTo(200);
        assertThat(admin.delete("/api/v1/comments/" + comment).status()).isEqualTo(204);

        ApiClient.Response log = admin.get(AUDIT + "?actorId=" + adminId + "&size=50");
        assertThat(log.json().at("/totalElements").asLong()).isEqualTo(4);
        assertThat(log.body())
                .contains("PATCH /api/v1/books/{id}", "PATCH /api/v1/chapters/{chapterId}",
                        "PATCH /api/v1/comments/{commentId}", "DELETE /api/v1/comments/{commentId}")
                .doesNotContain("Texto privado", "Moderado");
    }

    @Test
    void readingAndWorkOnOwnContentAreNotRecorded() {
        ApiClient admin = withRole("ADMIN");
        String adminId = userId(admin);
        String own = books.publishedBook(admin);
        admin.patch("/api/v1/books/" + own, body("synopsis", "Minha sinopse"));
        String comment = admin.post("/api/v1/books/" + own + "/comments", body("content", "Meu comentário")).json().at("/id").asString();
        admin.patch("/api/v1/comments/" + comment, body("content", "Corrigido"));
        admin.get("/api/v1/admin/users");
        admin.get(AUDIT);
        ApiClient user = newUser();
        String userBook = books.publishedBook(user);
        user.patch("/api/v1/books/" + userBook, body("synopsis", "Outra"));

        assertThat(entries(adminId)).isZero();
        assertThat(entries(userId(user))).isZero();
    }

    @Test
    void taxonomyChangesAreRecorded() {
        ApiClient admin = withRole("ADMIN");
        String adminId = userId(admin);
        String name = "Tag " + UUID.randomUUID().toString().substring(0, 8);

        assertThat(admin.post("/api/v1/admin/tags", body("name", name)).status()).isEqualTo(201);

        assertThat(admin.get(AUDIT + "?actorId=" + adminId).json().at("/content/0/action").asString())
                .isEqualTo("POST /api/v1/admin/tags");
    }

    @Test
    void actionsThatFailInsideARouteAreRecorded() {
        ApiClient moderator = withRole("MODERATOR");

        moderator.patch("/api/v1/moderation/reports/" + UUID.randomUUID(), body("status", "RESOLVED"));

        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM audit_log WHERE actor_id = ? AND action = 'PATCH /api/v1/moderation/reports/{id}'
                """, String.class, UUID.fromString(userId(moderator)))).isEqualTo("FAILED");
    }

    @Test
    void attemptsRefusedBySecurityAreRecordedAsDenied() {
        ApiClient admin = withRole("ADMIN");
        String target = userId(newUser());
        ApiClient user = newUser();
        ApiClient moderator = withRole("MODERATOR");

        assertThat(admin.patch("/api/v1/admin/users/" + target, body("role", "ROOT")).status()).isEqualTo(403);
        assertThat(user.get("/api/v1/moderation/reports").status()).isEqualTo(403);
        assertThat(moderator.get(AUDIT).status()).isEqualTo(403);

        assertThat(jdbc.queryForObject("SELECT outcome FROM audit_log WHERE actor_id = ?", String.class,
                UUID.fromString(userId(admin)))).isEqualTo("DENIED");
        assertThat(jdbc.queryForObject("SELECT action FROM audit_log WHERE actor_id = ?", String.class,
                UUID.fromString(userId(user)))).isEqualTo("GET /api/v1/moderation/reports");
        assertThat(entries(userId(moderator))).isEqualTo(1);
    }

    @Test
    void anonymousAttemptsAndMissingCsrfAreNotRecorded() {
        long before = jdbc.queryForObject("SELECT count(*) FROM audit_log", Long.class);
        ApiClient admin = withRole("ADMIN");

        assertThat(accounts.client().get(AUDIT).status()).isEqualTo(401);
        assertThat(admin.postWithoutCsrf("/api/v1/admin/tags", body("name", "Sem CSRF")).status()).isEqualTo(403);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Long.class)).isEqualTo(before);
    }

    @Test
    void theLogHasNoRouteThatChangesIt() {
        ApiClient root = withRole("ROOT");

        assertThat(root.post(AUDIT, body()).status()).isEqualTo(405);
        assertThat(root.delete(AUDIT).status()).isEqualTo(405);
    }

    @Test
    void listIsNewestFirstAndPaginated() {
        ApiClient root = withRole("ROOT");
        String id = userId(newUser());
        root.patch("/api/v1/admin/users/" + id, body("role", "MODERATOR"));
        root.patch("/api/v1/admin/users/" + id, body("role", "ADMIN"));

        JsonNode list = root.get(AUDIT + "?resource=users/" + id + "&size=1").json();
        assertThat(list.at("/totalElements").asLong()).isEqualTo(2);
        assertThat(list.at("/content").size()).isEqualTo(1);
        assertThat(list.at("/content/0/details/role/to").asString()).isEqualTo("ADMIN");
        assertThat(root.get(AUDIT + "?size=51").code()).isEqualTo("INVALID_PAGINATION");
    }
}
