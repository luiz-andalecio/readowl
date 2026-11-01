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

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * O acesso total da administração continua, mas deixa rastro: quem leu o quê e o que foi alterado.
 */
@ApiIntegrationTest
class SensitiveAuditIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;
    private BookFixtures books;
    private ApiClient admin;
    private UUID adminId;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        books = new BookFixtures(jdbc);
        mail.setFailing(false);
        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);
        admin = accounts.loggedIn(email, PASSWORD);
        adminId = accounts.userId(email);
    }

    private ApiClient newUser() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private List<String> actions() {
        return jdbc.queryForList("SELECT action FROM audit_log WHERE actor_id = ?", String.class, adminId);
    }

    private String details(String action) {
        return jdbc.queryForObject("SELECT details::text FROM audit_log WHERE actor_id = ? AND action = ?", String.class,
                adminId, action);
    }

    @Test
    void readingSomeoneElsesDraftIsRecorded() {
        ApiClient author = newUser();
        String chapter = books.createChapter(author, books.createBook(author), "Privado");

        assertThat(admin.get("/api/v1/chapters/" + chapter + "/draft").status()).isEqualTo(200);

        assertThat(actions()).containsExactly("GET /api/v1/chapters/{chapterId}/draft");
    }

    @Test
    void readingYourOwnDraftIsNot() {
        String chapter = books.createChapter(admin, books.createBook(admin), "Meu");

        assertThat(admin.get("/api/v1/chapters/" + chapter + "/draft").status()).isEqualTo(200);

        assertThat(actions()).isEmpty();
    }

    @Test
    void listingAndOpeningUsersIsRecorded() {
        assertThat(admin.get("/api/v1/admin/users").status()).isEqualTo(200);
        assertThat(admin.get("/api/v1/admin/users/" + adminId).status()).isEqualTo(200);

        assertThat(actions()).containsExactlyInAnyOrder("GET /api/v1/admin/users", "GET /api/v1/admin/users/{id}");
    }

    @Test
    void readingOtherAdministrativeListsIsNot() {
        assertThat(admin.get("/api/v1/admin/books").status()).isEqualTo(200);

        assertThat(actions()).isEmpty();
    }

    @Test
    void rewritingSomeoneElsesCommentKeepsBothTexts() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String comment = newUser().post("/api/v1/books/" + book + "/comments", body("content", "Texto original"))
                .json().at("/id").asString();

        assertThat(admin.patch("/api/v1/comments/" + comment, body("content", "Texto da administração")).status()).isEqualTo(200);

        assertThat(details("COMMENT_EDITED")).contains("Texto original").contains("Texto da administração");
        assertThat(actions()).containsExactly("COMMENT_EDITED");
    }

    @Test
    void deletingSomeoneElsesCommentKeepsItsText() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String comment = newUser().post("/api/v1/books/" + book + "/comments", body("content", "Comentário apagado"))
                .json().at("/id").asString();

        assertThat(admin.delete("/api/v1/comments/" + comment).status()).isEqualTo(204);

        assertThat(details("COMMENT_DELETED")).contains("Comentário apagado");
    }

    @Test
    void deletingSomeoneElsesBookAndChapterKeepsWhatTheyWere() {
        ApiClient author = newUser();
        String book = books.createBook(author, "Obra Apagada");
        String chapter = books.createChapter(author, book, "Capítulo Apagado");

        assertThat(admin.delete("/api/v1/chapters/" + chapter).status()).isEqualTo(204);
        assertThat(admin.delete("/api/v1/books/" + book, body("confirmTitle", "Obra Apagada")).status()).isEqualTo(204);

        assertThat(details("CHAPTER_DELETED")).contains("Capítulo Apagado");
        assertThat(details("BOOK_DELETED")).contains("Obra Apagada");
    }

    @Test
    void anAuthorDeletingTheirOwnBookIsNotRecorded() {
        String book = books.createBook(admin, "Minha Obra");

        assertThat(admin.delete("/api/v1/books/" + book, body("confirmTitle", "Minha Obra")).status()).isEqualTo(204);

        assertThat(actions()).isEmpty();
    }
}
