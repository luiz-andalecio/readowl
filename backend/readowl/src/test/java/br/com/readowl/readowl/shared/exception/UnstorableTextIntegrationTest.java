package br.com.readowl.readowl.shared.exception;

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

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * O caractere nulo não pode ser guardado pelo PostgreSQL. A resposta precisa apontar o problema, e não um
 * conflito com dados existentes.
 */
@ApiIntegrationTest
class UnstorableTextIntegrationTest {

    private static final String NUL = "nulo\u0000aqui";

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;
    private BookFixtures books;
    private ApiClient user;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        books = new BookFixtures(jdbc);
        mail.setFailing(false);
        String email = uniqueEmail();
        accounts.registerVerified(email);
        user = accounts.loggedIn(email, PASSWORD);
    }

    @Test
    void refusedInRequestBodies() {
        String book = books.publishedBook(user);
        String chapter = books.createChapter(user, book, "Sonda");
        String draftPath = "/api/v1/chapters/" + chapter + "/draft";
        long version = user.get(draftPath).json().at("/version").asLong();

        assertThat(user.post("/api/v1/books", books.newBook(NUL, BookFixtures.uniqueAcronym())).code()).isEqualTo("INVALID_TEXT");
        assertThat(user.post("/api/v1/books/" + book + "/chapters", body("title", NUL)).code()).isEqualTo("INVALID_TEXT");
        assertThat(user.patch("/api/v1/users/me", body("name", NUL)).code()).isEqualTo("INVALID_TEXT");
        assertThat(user.put(draftPath, body("version", version, "document", BookFixtures.document(NUL))).code()).isEqualTo("INVALID_TEXT");
        assertThat(accounts.client().post("/api/v1/auth/login", body("email", "a" + NUL + "@example.com", "password", "x")).code())
                .isEqualTo("INVALID_TEXT");
    }

    @Test
    void refusedInQueryParameters() {
        ApiClient anonymous = accounts.client();

        assertThat(anonymous.get("/api/v1/books?query=a%00b").code()).isEqualTo("INVALID_TEXT");
        assertThat(anonymous.get("/api/v1/books?author=a%00b").code()).isEqualTo("INVALID_TEXT");
        assertThat(anonymous.get("/api/v1/books?tag=a%00b").code()).isEqualTo("INVALID_TEXT");
        assertThat(anonymous.get("/api/v1/tags?query=a%00b").code()).isEqualTo("INVALID_TEXT");
    }

    @Test
    void nothingIsLeftBehindAndTheAccountKeepsWorking() {
        String book = books.createBook(user, "Antes");

        assertThat(user.patch("/api/v1/books/" + book, body("title", NUL)).code()).isEqualTo("INVALID_TEXT");

        assertThat(user.get("/api/v1/books/" + book + "/details").json().at("/title").asString()).isEqualTo("Antes");
        assertThat(user.patch("/api/v1/books/" + book, body("title", "Depois")).status()).isEqualTo(200);
    }
}
