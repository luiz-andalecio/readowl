package br.com.readowl.readowl.community;

import br.com.readowl.readowl.community.application.ChapterViewService;
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

import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class ChapterViewPurgeIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ChapterViewService views;

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

    private int rows(UUID chapter) {
        return jdbc.queryForObject("SELECT count(*) FROM chapter_views WHERE chapter_id = ?", Integer.class, chapter);
    }

    private long viewCount(UUID chapter) {
        return jdbc.queryForObject("SELECT view_count FROM chapters WHERE id = ?", Long.class, chapter);
    }

    @Test
    void onlyRowsThatNoLongerHaveAnEffectAreRemovedAndCountersStay() {
        ApiClient author = newUser();
        String book = books.publishedBook(author);
        UUID chapter = UUID.fromString(author.get("/api/v1/books/" + book + "/contents").json().at("/chapters/0/id").asString());
        ApiClient oldReader = newUser();
        ApiClient recentReader = newUser();
        String path = "/api/v1/chapters/" + chapter + "/views";
        assertThat(oldReader.post(path, body()).status()).isEqualTo(204);
        assertThat(recentReader.post(path, body()).status()).isEqualTo(204);
        UUID old = UUID.fromString(oldReader.get("/api/v1/auth/session").json().at("/user/id").asString());
        jdbc.update("UPDATE chapter_views SET last_counted_at = now() - interval '1 day' WHERE user_id = ?", old);

        views.purgeExpired();

        assertThat(rows(chapter)).isEqualTo(1);
        assertThat(viewCount(chapter)).isEqualTo(2);

        assertThat(recentReader.post(path, body()).status()).isEqualTo(204);
        assertThat(viewCount(chapter)).as("a linha recente continua deduplicando").isEqualTo(2);
        assertThat(oldReader.post(path, body()).status()).isEqualTo(204);
        assertThat(viewCount(chapter)).as("a linha removida conta como uma visita nova").isEqualTo(3);
    }
}
