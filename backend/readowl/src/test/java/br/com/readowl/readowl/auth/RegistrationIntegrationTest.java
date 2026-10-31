package br.com.readowl.readowl.auth;

import br.com.readowl.readowl.auth.application.AccountMailRequested;
import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.registration;
import static br.com.readowl.readowl.support.AccountFixtures.unique;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class RegistrationIntegrationTest {

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String VERIFY = "/api/v1/auth/verify-email";
    private static final String RESEND = "/api/v1/auth/resend-verification";

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionOperations transaction;

    private AccountFixtures accounts;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        mail.setFailing(false);
    }

    @Test
    void registrationStoresOnlyHashesAndSendsVerificationAfterCommit() {
        String email = uniqueEmail();

        ApiClient.Response response = accounts.client().post(REGISTER, registration(unique(), email, PASSWORD));

        assertThat(response.status()).isEqualTo(202);
        String token = mail.awaitToken(email);

        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(passwordHash).startsWith("{argon2id}").doesNotContain(PASSWORD);

        String tokenHash = jdbc.queryForObject("""
                SELECT t.token_hash FROM verification_tokens t JOIN users u ON u.id = t.user_id WHERE u.email = ?
                """, String.class, email);
        assertThat(tokenHash).hasSize(64).isNotEqualTo(token);
    }

    @Test
    void verificationLinkPointsToThePublicSiteAndCarriesTokenInFragment() {
        String email = uniqueEmail();
        accounts.client().post(REGISTER, registration(unique(), email, PASSWORD));

        SimpleMailMessage message = mail.awaitMessage(email);

        assertThat(message.getFrom()).isEqualTo("no-reply@readowl.test");
        assertThat(message.getText()).contains("https://readowl.test/verificar-email#token=");
    }

    @Test
    void verifiedAccountCanLogInButTokenWorksOnlyOnce() {
        String email = uniqueEmail();
        accounts.registerUnverified(email);
        String token = mail.awaitToken(email);

        assertThat(accounts.client().post(VERIFY, body("token", token)).status()).isEqualTo(204);

        ApiClient.Response reused = accounts.client().post(VERIFY, body("token", token));
        assertThat(reused.status()).isEqualTo(400);
        assertThat(reused.code()).isEqualTo("INVALID_TOKEN");

        assertThat(accounts.client().post("/api/v1/auth/login", body("email", email, "password", PASSWORD)).status())
                .isEqualTo(200);
    }

    @Test
    void expiredVerificationTokenIsRejected() {
        String email = uniqueEmail();
        accounts.registerUnverified(email);
        String token = mail.awaitToken(email);
        jdbc.update("""
                UPDATE verification_tokens SET expires_at = now() - interval '1 second'
                WHERE user_id = (SELECT id FROM users WHERE email = ?)
                """, email);

        ApiClient.Response response = accounts.client().post(VERIFY, body("token", token));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void duplicateEmailWithDifferentCaseGetsSameResponseAndKeepsTheExistingAccount() throws Exception {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        String originalHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);

        ApiClient.Response response = accounts.client()
                .post(REGISTER, registration(unique(), email.toUpperCase(), "outra-senha-456"));

        assertThat(response.status()).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = ?", Integer.class, email))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email))
                .isEqualTo(originalHash);

        SimpleMailMessage notice = mail.awaitMessage(email);
        assertThat(notice.getText()).contains("/recuperar-senha").doesNotContain("#token=");
    }

    @Test
    void reRegisteringAnUnverifiedEmailDoesNotConfirmThePreRegisteredPassword() throws Exception {
        String email = uniqueEmail();
        accounts.registerUnverified(email);
        mail.awaitMessage(email);

        accounts.client().post(REGISTER, registration(unique(), email, "senha-do-dono-456"));

        assertThat(mail.awaitMessage(email).getText()).doesNotContain("#token=");
        assertThat(mail.receivesNothing(email)).isTrue();
    }

    @Test
    void takenAndReservedUsernamesAreRejected() {
        String username = accounts.registerUnverified(uniqueEmail());

        ApiClient.Response taken = accounts.client().post(REGISTER, registration(username.toUpperCase(), uniqueEmail(), PASSWORD));
        ApiClient.Response reserved = accounts.client().post(REGISTER, registration("Admin", uniqueEmail(), PASSWORD));

        assertThat(taken.status()).isEqualTo(409);
        assertThat(taken.code()).isEqualTo("USERNAME_TAKEN");
        assertThat(reserved.status()).isEqualTo(409);
        assertThat(reserved.code()).isEqualTo("USERNAME_TAKEN");
    }

    @Test
    void simultaneousRegistrationsOfTheSameEmailCreateOneAccount() {
        String email = uniqueEmail();

        List<CompletableFuture<ApiClient.Response>> requests = List.of(
                CompletableFuture.supplyAsync(() -> accounts.client().post(REGISTER, registration(unique(), email, PASSWORD))),
                CompletableFuture.supplyAsync(() -> accounts.client().post(REGISTER, registration(unique(), email, PASSWORD))),
                CompletableFuture.supplyAsync(() -> accounts.client().post(REGISTER, registration(unique(), email, PASSWORD))));

        assertThat(requests).allSatisfy(request -> assertThat(request.join().status()).isEqualTo(202));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void invalidDataIsReportedByFieldInPortugueseWithoutEchoingThePassword() {
        ApiClient.Response response = accounts.client().post(REGISTER,
                body("name", " ", "username", "a!", "email", "invalido", "password", "curta"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.body()).contains("\"password\"").contains("\"email\"").contains("\"username\"")
                .contains("\"name\"").contains("tamanho").doesNotContain("curta");
    }

    @Test
    void resendWorksOnlyForUnverifiedAccountsAndAnswersTheSameForAnyEmail() throws Exception {
        String unverified = uniqueEmail();
        accounts.registerUnverified(unverified);
        String firstToken = mail.awaitToken(unverified);
        String verified = uniqueEmail();
        accounts.registerVerified(verified);
        String unknown = uniqueEmail();

        assertThat(accounts.client().post(RESEND, body("email", unverified)).status()).isEqualTo(202);
        assertThat(accounts.client().post(RESEND, body("email", verified)).status()).isEqualTo(202);
        assertThat(accounts.client().post(RESEND, body("email", unknown)).status()).isEqualTo(202);

        String newToken = mail.awaitToken(unverified);
        assertThat(mail.receivesNothing(verified)).isTrue();
        assertThat(mail.receivesNothing(unknown)).isTrue();

        assertThat(accounts.client().post(VERIFY, body("token", firstToken)).code()).isEqualTo("INVALID_TOKEN");
        assertThat(accounts.client().post(VERIFY, body("token", newToken)).status()).isEqualTo(204);
    }

    @Test
    void smtpFailureDoesNotFailRegistration() {
        mail.setFailing(true);
        String email = uniqueEmail();

        ApiClient.Response response = accounts.client().post(REGISTER, registration(unique(), email, PASSWORD));

        assertThat(response.status()).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void rolledBackTransactionSendsNoEmail() throws Exception {
        String email = uniqueEmail();

        transaction.executeWithoutResult(status -> {
            events.publishEvent(new AccountMailRequested(AccountMailRequested.Type.ACCOUNT_ALREADY_EXISTS, email, null));
            status.setRollbackOnly();
        });

        assertThat(mail.receivesNothing(email)).isTrue();
    }

    @Test
    void registrationIsLimitedPerEmail() {
        String email = uniqueEmail();
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(accounts.client().post(REGISTER, registration(unique(), email, PASSWORD)).status()).isEqualTo(202);
        }

        ApiClient.Response limited = accounts.client().post(REGISTER, registration(unique(), email, PASSWORD));

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()))
                .isBetween(1L, Duration.ofHours(1).toSeconds());
    }
}
