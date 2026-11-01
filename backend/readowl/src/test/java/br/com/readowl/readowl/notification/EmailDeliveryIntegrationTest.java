package br.com.readowl.readowl.notification;

import br.com.readowl.readowl.notification.application.NotificationWorker;
import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.BookFixtures;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.AfterEach;
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
 * Enviar fora de transação: um SMTP lento não prende conexões, e a falha de um e-mail não desfaz os outros.
 */
@ApiIntegrationTest
class EmailDeliveryIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationWorker worker;

    private AccountFixtures accounts;
    private BookFixtures books;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        books = new BookFixtures(jdbc);
        mail.setFailing(false);
        jdbc.update("UPDATE notifications SET email_status = 'SKIPPED' WHERE email_status = 'PENDING'");
    }

    @AfterEach
    void tearDown() {
        mail.breakFor(null);
    }

    private ApiClient newUser(String email) {
        accounts.registerVerified(email);
        return accounts.loggedIn(email, PASSWORD);
    }

    private String status(String email) {
        return jdbc.queryForObject("""
                SELECT n.email_status FROM notifications n JOIN users u ON u.id = n.user_id WHERE u.email = ?
                """, String.class, email);
    }

    /**
     * Uma obra com dois seguidores que aceitaram receber e-mails, e um capítulo novo pendente de envio.
     */
    private void publishChapterForFollowers(String firstEmail, String secondEmail) {
        ApiClient author = newUser(uniqueEmail());
        String book = books.publishedBook(author);
        for (String email : new String[]{firstEmail, secondEmail}) {
            ApiClient follower = newUser(email);
            follower.patch("/api/v1/users/me", body("emailNewChapters", true));
            assertThat(follower.put("/api/v1/library/" + book, body()).status()).isEqualTo(200);
        }
        books.publishChapter(author, books.createChapter(author, book, "Capítulo novo"), "Texto novo");
        worker.processEvents();
    }

    @Test
    void anUnexpectedErrorOnOneEmailDoesNotAffectTheOthers() {
        String broken = uniqueEmail();
        String healthy = uniqueEmail();
        publishChapterForFollowers(broken, healthy);
        mail.breakFor(broken);

        worker.deliverEmails();

        assertThat(status(healthy)).isEqualTo("SENT");
        assertThat(mail.awaitMessage(healthy).getSubject()).contains("Novo capítulo");
        assertThat(status(broken)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT n.email_last_error FROM notifications n JOIN users u ON u.id = n.user_id WHERE u.email = ?
                """, String.class, broken)).isEqualTo("IllegalStateException");
    }

    @Test
    void sendingHappensOutsideATransactionAndAClaimedEmailIsNotTakenTwice() throws Exception {
        String first = uniqueEmail();
        String second = uniqueEmail();
        publishChapterForFollowers(first, second);
        jdbc.update("""
                UPDATE notifications n SET email_next_attempt_at = now() + interval '10 minutes'
                FROM users u WHERE u.id = n.user_id AND u.email = ?
                """, second);

        worker.deliverEmails();

        assertThat(mail.sentInsideTransaction()).isFalse();
        assertThat(status(first)).isEqualTo("SENT");
        assertThat(status(second)).as("já reservado por outra instância").isEqualTo("PENDING");
        assertThat(mail.receivesNothing(second)).isTrue();
    }
}
