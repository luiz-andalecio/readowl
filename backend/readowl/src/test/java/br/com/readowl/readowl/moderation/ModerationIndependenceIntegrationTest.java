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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A prova sobrevive enquanto o conteúdo estiver oculto, e ninguém da moderação decide sobre o que é seu.
 */
@ApiIntegrationTest
class ModerationIndependenceIntegrationTest {

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

    private static String deleteBook(String book) {
        return "/api/v1/books/" + book + "?confirmTitle=" + URLEncoder.encode("Obra de Teste", StandardCharsets.UTF_8);
    }

    private String secondChapter(ApiClient author, String book) {
        String chapter = books.createChapter(author, book, "Capítulo 2");
        books.publishChapter(author, chapter, "Continua.");
        return chapter;
    }

    @Test
    void aHiddenBookCannotBeDeletedUntilRestored() {
        ApiClient author = withRole("USER");
        String book = books.publishedBook(author);
        assertThat(withRole("MODERATOR").post("/api/v1/moderation/books/" + book + "/hide", REASON).status()).isEqualTo(204);

        ApiClient.Response refused = author.delete(deleteBook(book));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("BOOK_UNDER_MODERATION");
        assertThat(withRole("ADMIN").delete(deleteBook(book)).code()).isEqualTo("BOOK_UNDER_MODERATION");

        assertThat(withRole("MODERATOR").post("/api/v1/moderation/books/" + book + "/restore", REASON).status()).isEqualTo(204);
        assertThat(author.delete(deleteBook(book)).status()).isEqualTo(204);
    }

    @Test
    void aHiddenChapterCannotBeDeletedDirectlyOrWithItsBook() {
        ApiClient author = withRole("USER");
        String book = books.publishedBook(author);
        String chapter = secondChapter(author, book);
        ApiClient moderator = withRole("MODERATOR");
        assertThat(moderator.post("/api/v1/moderation/chapters/" + chapter + "/hide", REASON).status()).isEqualTo(204);
        assertThat(author.post("/api/v1/books/" + book + "/unpublish", body()).status()).isEqualTo(200);

        assertThat(author.delete("/api/v1/chapters/" + chapter).code()).isEqualTo("CHAPTER_UNDER_MODERATION");
        assertThat(author.delete(deleteBook(book)).code()).isEqualTo("BOOK_UNDER_MODERATION");

        assertThat(moderator.post("/api/v1/moderation/chapters/" + chapter + "/restore", REASON).status()).isEqualTo(204);
        assertThat(author.delete("/api/v1/chapters/" + chapter).status()).isEqualTo(204);
    }

    @Test
    void aModeratorCannotRestoreTheirOwnContent() {
        ApiClient owner = withRole("MODERATOR");
        ApiClient colleague = withRole("MODERATOR");
        String book = books.publishedBook(owner);
        String chapter = secondChapter(owner, book);
        String comment = owner.post("/api/v1/books/" + book + "/comments", body("content", "Meu comentário"))
                .json().at("/id").asString();
        assertThat(colleague.post("/api/v1/moderation/comments/" + comment + "/hide", REASON).status()).isEqualTo(204);
        assertThat(colleague.post("/api/v1/moderation/chapters/" + chapter + "/hide", REASON).status()).isEqualTo(204);
        assertThat(colleague.post("/api/v1/moderation/books/" + book + "/hide", REASON).status()).isEqualTo(204);

        for (String path : new String[]{"books/" + book, "chapters/" + chapter, "comments/" + comment}) {
            ApiClient.Response refused = owner.post("/api/v1/moderation/" + path + "/restore", REASON);
            assertThat(refused.status()).isEqualTo(403);
            assertThat(refused.code()).isEqualTo("OWN_CONTENT_MODERATION");
            assertThat(colleague.post("/api/v1/moderation/" + path + "/restore", REASON).status()).isEqualTo(204);
        }
    }

    @Test
    void aModeratorCannotCloseAReportAgainstThemselves() {
        ApiClient owner = withRole("MODERATOR");
        String book = books.publishedBook(owner);
        String report = withRole("USER").post("/api/v1/reports", body("targetType", "BOOK", "targetId", book, "reason", "SPAM"))
                .json().at("/id").asString();

        ApiClient.Response refused = owner.patch("/api/v1/moderation/reports/" + report, body("status", "DISMISSED"));

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo("OWN_CONTENT_MODERATION");
        assertThat(withRole("MODERATOR").patch("/api/v1/moderation/reports/" + report, body("status", "DISMISSED")).status())
                .isEqualTo(200);
    }

    @Test
    void administratorsKeepFullAccessOverTheirOwnContent() {
        ApiClient admin = withRole("ADMIN");
        String book = books.publishedBook(admin);
        assertThat(withRole("MODERATOR").post("/api/v1/moderation/books/" + book + "/hide", REASON).status()).isEqualTo(204);

        assertThat(admin.post("/api/v1/moderation/books/" + book + "/restore", REASON).status()).isEqualTo(204);
    }
}
