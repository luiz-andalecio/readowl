package br.com.readowl.readowl.shared.persistence;

import br.com.readowl.readowl.notification.application.NotificationWorker;
import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.RecordingMailSender;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pede a mesma lista com páginas de tamanhos diferentes: se o número de comandos SQL mudar, há uma consulta por
 * item. Só conta o que passa pelo Hibernate. O catálogo usa JdbcTemplate, com consultas em lote.
 */
@ApiIntegrationTest
class QueryCountIntegrationTest {

    private static final int ITEMS = 6;

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private NotificationWorker worker;

    private AccountFixtures accounts;
    private BookFixtures books;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        books = new BookFixtures(jdbc);
        mail.setFailing(false);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @AfterEach
    void tearDown() {
        statistics.setStatisticsEnabled(false);
    }

    /**
     * Estes testes criam mais contas do que o limite por IP permite.
     */
    private ApiClient newUser() {
        accounts.resetRateLimits();
        String email = uniqueEmail();
        accounts.registerVerified(email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private ApiClient withRole(String role) {
        accounts.resetRateLimits();
        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("UPDATE users SET role = ? WHERE email = ?", role, email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private long statementsFor(ApiClient client, String path) {
        statistics.clear();
        assertThat(client.get(path).status()).isEqualTo(200);
        return statistics.getPrepareStatementCount();
    }

    private void assertNoQueryPerItem(ApiClient client, String path) {
        String separator = path.contains("?") ? "&" : "?";
        long small = statementsFor(client, path + separator + "size=1");
        long large = statementsFor(client, path + separator + "size=" + ITEMS);
        assertThat(client.get(path + separator + "size=" + ITEMS).json().at("/content").size()).as(path).isEqualTo(ITEMS);
        assertThat(large).as(path).isEqualTo(small);
    }

    private List<String> publishedBooks(ApiClient author) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < ITEMS; i++) {
            ids.add(books.publishedBook(author));
        }
        return ids;
    }

    @Test
    void booksAndLibrary() {
        ApiClient author = newUser();
        List<String> ids = publishedBooks(author);
        ApiClient reader = newUser();
        ids.forEach(id -> reader.put("/api/v1/library/" + id, body()));
        String chapter = author.get("/api/v1/books/" + ids.getFirst() + "/contents").json().at("/chapters/0/id").asString();
        ids.forEach(id -> reader.put("/api/v1/library/" + id + "/progress", body("chapterId",
                author.get("/api/v1/books/" + id + "/contents").json().at("/chapters/0/id").asString(), "percent", 10)));

        assertNoQueryPerItem(author, "/api/v1/users/me/books");
        assertNoQueryPerItem(withRole("ADMIN"), "/api/v1/admin/books");
        assertNoQueryPerItem(reader, "/api/v1/library");
        assertThat(chapter).isNotBlank();
    }

    @Test
    void chaptersAndRevisions() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = books.createChapter(author, book, "Revisado");
        for (int i = 0; i < ITEMS; i++) {
            books.publishChapter(author, chapter, "Texto " + i);
        }
        String slug = author.get("/api/v1/books/" + book + "/details").json().at("/slug").asString();

        assertNoQueryPerItem(author, "/api/v1/chapters/" + chapter + "/revisions");

        long few = statementsFor(accounts.client(), "/api/v1/books/" + slug);
        long fewContents = statementsFor(author, "/api/v1/books/" + book + "/contents");
        for (int i = 0; i < ITEMS; i++) {
            books.publishChapter(author, books.createChapter(author, book, "Capítulo extra " + i), "Texto");
        }
        assertThat(statementsFor(accounts.client(), "/api/v1/books/" + slug)).isEqualTo(few);
        assertThat(statementsFor(author, "/api/v1/books/" + book + "/contents")).isEqualTo(fewContents);
    }

    @Test
    void commentsAndModeration() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String chapter = author.get("/api/v1/books/" + book + "/contents").json().at("/chapters/0/id").asString();
        List<String> main = new ArrayList<>();
        for (int i = 0; i < ITEMS; i++) {
            ApiClient reader = newUser();
            main.add(reader.post("/api/v1/books/" + book + "/comments", body("content", "Na obra " + i)).json().at("/id").asString());
            reader.post("/api/v1/chapters/" + chapter + "/comments", body("content", "No capítulo " + i));
            reader.post("/api/v1/comments/" + main.getFirst() + "/replies", body("content", "Resposta " + i));
            newUser().put("/api/v1/comments/" + main.get(i) + "/like", body());
        }
        ApiClient moderator = withRole("MODERATOR");
        ApiClient reporter = newUser();
        main.forEach(id -> reporter.post("/api/v1/reports", body("targetType", "COMMENT", "targetId", id, "reason", "SPAM")));
        moderator.patch("/api/v1/moderation/reports/" + moderator.get("/api/v1/moderation/reports?status=OPEN")
                .json().at("/content/0/id").asString(), body("status", "DISMISSED"));
        moderator.post("/api/v1/moderation/comments/" + main.getFirst() + "/hide", body("reason", "Teste"));
        ApiClient anonymous = accounts.client();

        assertNoQueryPerItem(anonymous, "/api/v1/books/" + book + "/comments");
        assertNoQueryPerItem(anonymous, "/api/v1/chapters/" + chapter + "/comments");
        assertNoQueryPerItem(anonymous, "/api/v1/comments/" + main.getFirst() + "/replies");
        assertNoQueryPerItem(moderator, "/api/v1/moderation/reports");
        assertNoQueryPerItem(moderator, "/api/v1/moderation/comments?bookId=" + book);
    }

    @Test
    void notificationsAuditAndAdministration() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        ApiClient reader = newUser();
        reader.put("/api/v1/library/" + book, body());
        for (int i = 0; i < ITEMS; i++) {
            books.publishChapter(author, books.createChapter(author, book, "Novo " + i), "Texto");
        }
        worker.processEvents();
        ApiClient admin = withRole("ADMIN");
        for (int i = 0; i < ITEMS; i++) {
            admin.post("/api/v1/admin/banners", body("name", "Banner " + i, "imageUrl", "https://cdn.example.com/a.png",
                    "linkUrl", "https://readowl.example.com", "position", i, "enabled", true));
            newUser();
        }

        assertNoQueryPerItem(reader, "/api/v1/notifications");
        assertNoQueryPerItem(admin, "/api/v1/admin/audit-log");
        assertNoQueryPerItem(admin, "/api/v1/admin/banners");
        assertNoQueryPerItem(admin, "/api/v1/admin/users");
    }
}
