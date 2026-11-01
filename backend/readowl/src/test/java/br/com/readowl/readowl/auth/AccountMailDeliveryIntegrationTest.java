package br.com.readowl.readowl.auth;

import br.com.readowl.readowl.auth.application.AccountMails;
import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.registration;
import static br.com.readowl.readowl.support.AccountFixtures.unique;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * O pedido fica gravado até o e-mail sair: nem o SMTP fora do ar nem um reinício perdem a confirmação.
 */
@ApiIntegrationTest
class AccountMailDeliveryIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountMails mails;

    private AccountFixtures accounts;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        mail.setFailing(false);
    }

    @AfterEach
    void tearDown() {
        mail.setFailing(false);
    }

    private int queued(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM account_mail_queue WHERE email = ?", Integer.class, email);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant limit = Instant.now().plus(Duration.ofSeconds(5));
        while (!condition.getAsBoolean() && Instant.now().isBefore(limit)) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private void makeDue(String email) {
        jdbc.update("UPDATE account_mail_queue SET next_attempt_at = now() - interval '1 second' WHERE email = ?", email);
    }

    private void register(String email) {
        assertThat(accounts.client().post("/api/v1/auth/register", registration(unique(), email, PASSWORD)).status())
                .isEqualTo(202);
    }

    @Test
    void aDeliveredMailLeavesNoRow() throws Exception {
        String email = uniqueEmail();

        register(email);
        mail.awaitToken(email);

        await(() -> queued(email) == 0);
    }

    @Test
    void whenTheServerComesBackTheConfirmationGoesOutAndItsLinkWorks() throws Exception {
        String email = uniqueEmail();
        mail.setFailing(true);
        register(email);
        await(() -> jdbc.queryForObject("SELECT attempts FROM account_mail_queue WHERE email = ?", Integer.class, email) == 1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM account_mail_queue WHERE email = ?", String.class, email))
                .isEqualTo("MailSendException");

        mail.setFailing(false);
        makeDue(email);
        mails.deliverDue();

        assertThat(accounts.client().post("/api/v1/auth/verify-email", body("token", mail.awaitToken(email))).status())
                .isEqualTo(204);
        assertThat(queued(email)).isZero();
    }

    @Test
    void aPendingRequestSurvivesARestart() {
        String email = uniqueEmail();
        accounts.registerUnverified(email);
        mail.awaitToken(email);
        jdbc.update("""
                INSERT INTO account_mail_queue (id, type, email, created_at, next_attempt_at)
                VALUES (?, 'VERIFY_EMAIL', ?, now() - interval '1 hour', now() - interval '1 hour')
                """, UUID.randomUUID(), email);

        mails.deliverDue();

        assertThat(accounts.client().post("/api/v1/auth/verify-email", body("token", mail.awaitToken(email))).status())
                .isEqualTo(204);
    }

    @Test
    void anotherInstanceDoesNotTakeAMailBeingSent() throws Exception {
        String email = uniqueEmail();
        jdbc.update("""
                INSERT INTO account_mail_queue (id, type, email, created_at, next_attempt_at)
                VALUES (?, 'ACCOUNT_ALREADY_EXISTS', ?, now(), now() + interval '5 minutes')
                """, UUID.randomUUID(), email);

        mails.deliverDue();

        assertThat(mail.receivesNothing(email)).isTrue();
        assertThat(queued(email)).isEqualTo(1);
    }

    @Test
    void afterTheLastAttemptTheFailureStaysRecordedAndIsNotRetried() throws Exception {
        String email = uniqueEmail();
        mail.setFailing(true);
        register(email);
        await(() -> jdbc.queryForObject("SELECT attempts FROM account_mail_queue WHERE email = ?", Integer.class, email) == 1);
        for (int attempt = 2; attempt <= 5; attempt++) {
            makeDue(email);
            mails.deliverDue();
        }
        assertThat(jdbc.queryForObject("SELECT failed_at IS NOT NULL FROM account_mail_queue WHERE email = ?", Boolean.class, email))
                .isTrue();

        mail.setFailing(false);
        makeDue(email);
        mails.deliverDue();

        assertThat(mail.receivesNothing(email)).isTrue();
    }

    @Test
    void aConfirmationIsNotSentIfTheAccountWasConfirmedInTheMeantime() throws Exception {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        jdbc.update("""
                INSERT INTO account_mail_queue (id, type, email, created_at, next_attempt_at)
                VALUES (?, 'VERIFY_EMAIL', ?, now(), now() - interval '1 second')
                """, UUID.randomUUID(), email);

        mails.deliverDue();

        assertThat(mail.receivesNothing(email)).isTrue();
        assertThat(queued(email)).isZero();
    }
}
