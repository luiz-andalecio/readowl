package br.com.readowl.readowl.shared.api;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class PublicResponsesIntegrationTest {

    private static final String CACHE_CONTROL = "Cache-Control";

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private HealthEndpointGroups healthGroups;

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

    private static String cacheControl(ApiClient.Response response) {
        return response.headers().firstValue(CACHE_CONTROL).orElse("");
    }

    @Test
    void publicContentCanBeCachedForAShortTime() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        String slug = author.get("/api/v1/books/" + book + "/details").json().at("/slug").asString();
        ApiClient visitor = accounts.client();

        for (String path : new String[]{"/api/v1/books", "/api/v1/books/" + slug, "/api/v1/books/" + slug + "/chapters",
                "/api/v1/books/" + slug + "/chapters/capitulo-1", "/api/v1/banners"}) {
            ApiClient.Response response = visitor.get(path);
            assertThat(response.status()).as(path).isEqualTo(200);
            assertThat(cacheControl(response)).as(path).contains("public").contains("max-age=30").doesNotContain("no-store");
            assertThat(response.setCookies()).as(path).isEmpty();
        }
        assertThat(cacheControl(visitor.get("/api/v1/genres"))).contains("max-age=300");
    }

    @Test
    void errorsAndPrivateResponsesAreNotCached() {
        ApiClient user = newUser();

        assertThat(cacheControl(accounts.client().get("/api/v1/books/nao-existe"))).contains("no-store");
        assertThat(cacheControl(user.get("/api/v1/users/me"))).contains("no-store");
        assertThat(cacheControl(user.get("/api/v1/library"))).contains("no-store");
    }

    @Test
    void securityHeadersAreSent() {
        ApiClient.Response response = accounts.client().get("/api/v1/books");

        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
    }

    @Test
    void publicRoutesAnswerHeadForMonitorsAndPrivateOnesDoNot() {
        assertThat(accounts.client().head("/api/v1/books").status()).isEqualTo(200);
        assertThat(accounts.client().head("/api/v1/users/me").status()).isEqualTo(401);
    }

    @Test
    void chaptersComeInPagesInReadingOrder() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        books.publishChapter(author, books.createChapter(author, book, "Capítulo 2"), "Texto");
        books.publishChapter(author, books.createChapter(author, book, "Capítulo 3"), "Texto");
        String slug = author.get("/api/v1/books/" + book + "/details").json().at("/slug").asString();
        ApiClient visitor = accounts.client();

        ApiClient.Response second = visitor.get("/api/v1/books/" + slug + "/chapters?page=1&size=2");

        assertThat(second.json().at("/content").size()).isEqualTo(1);
        assertThat(second.json().at("/content/0/slug").asString()).isEqualTo("capitulo-3");
        assertThat(second.json().at("/totalElements").asLong()).isEqualTo(3);
        assertThat(visitor.get("/api/v1/books/" + slug).json().at("/publishedChapters").asLong()).isEqualTo(3);
        assertThat(visitor.get("/api/v1/books/" + slug + "/chapters?size=51").code()).isEqualTo("INVALID_PAGINATION");
    }

    @Test
    void withoutTheDatabaseAnInstanceStopsReceivingTrafficButIsNotRestarted() {
        assertThat(healthGroups.get("readiness").isMember("db")).isTrue();
        assertThat(healthGroups.get("liveness").isMember("db")).isFalse();
    }
}
