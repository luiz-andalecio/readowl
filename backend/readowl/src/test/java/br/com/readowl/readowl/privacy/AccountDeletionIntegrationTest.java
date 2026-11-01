package br.com.readowl.readowl.privacy;

import br.com.readowl.readowl.privacy.application.AccountDeletionService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class AccountDeletionIntegrationTest {

    private static final String ME = "/api/v1/users/me";

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

    private ApiClient reader() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        return accounts.loggedIn(email, PASSWORD);
    }

    /**
     * Procura o texto em toda coluna de texto ou JSON de toda tabela, e assim alcança as tabelas criadas depois.
     */
    private List<String> columnsContaining(String text) {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT table_name, column_name FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND data_type IN ('character varying', 'text', 'jsonb', 'inet', 'uuid')
                  AND table_name <> 'flyway_schema_history'
                """);
        List<String> found = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            String name = column.get("table_name") + "." + column.get("column_name");
            Integer hits = jdbc.queryForObject("SELECT count(*) FROM " + column.get("table_name")
                    + " WHERE " + column.get("column_name") + "::text ILIKE ?", Integer.class, "%" + text + "%");
            if (hits != null && hits > 0) {
                found.add(name);
            }
        }
        return found;
    }

    @Test
    void aDeletedAccountLeavesNoPersonalDataInAnyTable() {
        String email = uniqueEmail();
        String username = accounts.registerVerified(email);
        UUID id = accounts.userId(email);
        ApiClient person = accounts.loggedIn(email, PASSWORD);
        String marker = "marca" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(person.patch(ME, body("name", marker, "description", "Sobre " + marker)).status()).isEqualTo(200);

        ApiClient author = reader();
        String book = books.publishedBook(author);
        assertThat(person.put("/api/v1/library/" + book, body()).status()).isIn(200, 204);
        assertThat(person.put("/api/v1/books/" + book + "/rating/me", body("score", 5)).status()).isIn(200, 204);
        assertThat(person.post("/api/v1/books/" + book + "/comments", body("content", "Gostei muito")).status()).isEqualTo(201);
        String own = books.createBook(person);
        books.createChapter(person, own, "Meu capítulo");

        assertThat(person.delete(ME, body("password", PASSWORD)).status()).isEqualTo(204);

        assertThat(columnsContaining(marker)).isEmpty();
        assertThat(columnsContaining(username)).isEmpty();
        // O e-mail só pode continuar no aviso de exclusão, até ele sair.
        assertThat(columnsContaining(email)).isSubsetOf("account_mail_queue.email");
        for (String table : AccountDeletionService.REMOVED_TABLES) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, id))
                    .as(table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM books WHERE author_id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForMap("SELECT status, password_hash, description, role FROM users WHERE id = ?", id))
                .containsEntry("status", "DELETED").containsEntry("password_hash", null)
                .containsEntry("description", null).containsEntry("role", "USER");
    }

    @Test
    void everyTableThatPointsToUsersHasADecision() {
        List<String> pointing = jdbc.queryForList("""
                SELECT DISTINCT source.relname FROM pg_constraint c
                JOIN pg_class source ON source.oid = c.conrelid
                JOIN pg_class target ON target.oid = c.confrelid
                JOIN pg_namespace n ON n.oid = target.relnamespace
                WHERE c.contype = 'f' AND target.relname = 'users' AND n.nspname = current_schema()
                """, String.class);
        Set<String> decided = new TreeSet<>(AccountDeletionService.REMOVED_TABLES);
        decided.addAll(AccountDeletionService.KEPT_TABLES);

        assertThat(decided).containsAll(pointing);
    }

    @Test
    void theCommentStaysWithItsTextAndWithoutTheAuthor() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient person = accounts.loggedIn(email, PASSWORD);
        ApiClient author = reader();
        String book = books.publishedBook(author);
        assertThat(person.post("/api/v1/books/" + book + "/comments", body("content", "Fica na conversa")).status()).isEqualTo(201);

        assertThat(person.delete(ME, body("password", PASSWORD)).status()).isEqualTo(204);

        var comment = accounts.client().get("/api/v1/books/" + book + "/comments").json().at("/content/0");
        assertThat(comment.at("/content").asString()).isEqualTo("Fica na conversa");
        assertThat(comment.at("/removed").asBoolean()).isFalse();
        assertThat(comment.at("/author/name").asString()).isEqualTo("Usuário removido");
        assertThat(comment.at("/author/username").isNull()).isTrue();
    }

    @Test
    void aBookUnderAnOpenReportIsKeptOffTheAirUntilTheReportCloses() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient person = accounts.loggedIn(email, PASSWORD);
        UUID id = accounts.userId(email);
        String book = books.publishedBook(person);
        String slug = person.get("/api/v1/books/" + book + "/details").json().at("/slug").asString();
        ApiClient reporter = reader();
        assertThat(reporter.post("/api/v1/reports", body("targetType", "BOOK", "targetId", book, "reason", "ABUSE")).status())
                .isEqualTo(201);

        assertThat(person.delete(ME, body("password", PASSWORD)).status()).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM books WHERE author_id = ?", Integer.class, id)).isOne();
        assertThat(accounts.client().get("/api/v1/books/" + slug).status()).isEqualTo(404);
    }

    @Test
    void theWrongPasswordDeletesNothing() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient person = accounts.loggedIn(email, PASSWORD);

        var refused = person.delete(ME, body("password", "errada-123"));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.code()).isEqualTo("INVALID_CURRENT_PASSWORD");
        assertThat(person.get(ME).status()).isEqualTo(200);
    }

    @Test
    void afterDeletionEverySessionEndsAndTheAddressCanRegisterAgain() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient first = accounts.loggedIn(email, PASSWORD);
        ApiClient second = accounts.loggedIn(email, PASSWORD);

        assertThat(first.delete(ME, body("password", PASSWORD)).status()).isEqualTo(204);

        assertThat(first.get(ME).status()).isEqualTo(401);
        assertThat(second.get(ME).status()).isEqualTo(401);
        assertThat(accounts.client().post("/api/v1/auth/login", body("email", email, "password", PASSWORD)).status()).isEqualTo(401);
        mail.awaitSubject(email, "foi excluída");
        accounts.registerVerified(email);
        accounts.loggedIn(email, PASSWORD);
    }

    @Test
    void theLastRootCannotLeave() {
        jdbc.update("UPDATE users SET role = 'USER' WHERE role = 'ROOT'");
        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("UPDATE users SET role = 'ROOT' WHERE email = ?", email);
        ApiClient root = accounts.loggedIn(email, PASSWORD);

        var refused = root.delete(ME, body("password", PASSWORD));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("LAST_ROOT");
        jdbc.update("UPDATE users SET role = 'USER' WHERE email = ?", email);
    }

    @Test
    void aDeletedAccountCannotBeChangedByAnAdministrator() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        UUID id = accounts.userId(email);
        assertThat(accounts.loggedIn(email, PASSWORD).delete(ME, body("password", PASSWORD)).status()).isEqualTo(204);
        String rootEmail = uniqueEmail();
        accounts.registerVerified(rootEmail);
        jdbc.update("UPDATE users SET role = 'ROOT' WHERE email = ?", rootEmail);
        ApiClient root = accounts.loggedIn(rootEmail, PASSWORD);

        assertThat(root.patch("/api/v1/admin/users/" + id, body("name", "De volta")).code()).isEqualTo("ACCOUNT_DELETED");
        String otherEmail = uniqueEmail();
        accounts.registerVerified(otherEmail);
        assertThat(root.patch("/api/v1/admin/users/" + accounts.userId(otherEmail), body("status", "DELETED")).code())
                .isEqualTo("INVALID_STATUS");
    }

    @Test
    void theAccessRecordsSurviveDeletion() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        UUID id = accounts.userId(email);

        assertThat(accounts.loggedIn(email, PASSWORD).delete(ME, body("password", PASSWORD)).status()).isEqualTo(204);

        assertThat(jdbc.queryForList("SELECT action FROM access_records WHERE user_id = ?", String.class, id))
                .contains("LOGIN_SUCCEEDED", "ACCOUNT_DELETED");
    }
}
