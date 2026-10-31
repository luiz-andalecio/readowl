package br.com.readowl.readowl.auth;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class PasswordRecoveryIntegrationTest {

    private static final String FORGOT = "/api/v1/auth/password/forgot";
    private static final String RESET = "/api/v1/auth/password/reset";
    private static final String CHANGE = "/api/v1/users/me/password";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PRIVATE = "/api/v1/users/me";
    private static final String NEW_PASSWORD = "nova-senha-789";

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountFixtures accounts;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        mail.setFailing(false);
    }

    private String verifiedAccount() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        return email;
    }

    private String requestResetToken(String email) {
        assertThat(accounts.client().post(FORGOT, body("email", email)).status()).isEqualTo(202);
        return mail.awaitToken(email);
    }

    @Test
    void forgotPasswordAnswersTheSameWhetherOrNotTheAccountExists() throws Exception {
        String existing = verifiedAccount();
        String unknown = uniqueEmail();

        ApiClient.Response forExisting = accounts.client().post(FORGOT, body("email", existing));
        ApiClient.Response forUnknown = accounts.client().post(FORGOT, body("email", unknown));

        assertThat(forExisting.status()).isEqualTo(202);
        assertThat(forUnknown.status()).isEqualTo(202);
        assertThat(forExisting.body()).isEqualTo(forUnknown.body());
        assertThat(mail.awaitMessage(existing).getText()).contains("https://readowl.test/redefinir-senha#token=");
        assertThat(mail.receivesNothing(unknown)).isTrue();
    }

    @Test
    void suspendedAccountReceivesNoRecoveryEmail() throws Exception {
        String email = verifiedAccount();
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email);

        assertThat(accounts.client().post(FORGOT, body("email", email)).status()).isEqualTo(202);

        assertThat(mail.receivesNothing(email)).isTrue();
    }

    @Test
    void resetReplacesThePasswordAndRevokesExistingSessions() {
        String email = verifiedAccount();
        ApiClient session = accounts.loggedIn(email, PASSWORD);
        String token = requestResetToken(email);

        assertThat(accounts.client().post(RESET, body("token", token, "newPassword", NEW_PASSWORD)).status())
                .isEqualTo(204);

        assertThat(session.get(PRIVATE).status()).isEqualTo(401);
        assertThat(accounts.client().post(LOGIN, body("email", email, "password", PASSWORD)).status()).isEqualTo(401);
        assertThat(accounts.client().post(LOGIN, body("email", email, "password", NEW_PASSWORD)).status()).isEqualTo(200);
    }

    @Test
    void resetTokenCannotBeReused() {
        String email = verifiedAccount();
        String token = requestResetToken(email);
        accounts.client().post(RESET, body("token", token, "newPassword", NEW_PASSWORD));

        ApiClient.Response reused = accounts.client().post(RESET, body("token", token, "newPassword", "outra-senha-000"));

        assertThat(reused.status()).isEqualTo(400);
        assertThat(reused.code()).isEqualTo("INVALID_TOKEN");
        assertThat(accounts.client().post(LOGIN, body("email", email, "password", NEW_PASSWORD)).status()).isEqualTo(200);
    }

    @Test
    void simultaneousUsesOfATokenSucceedOnlyOnce() {
        String email = verifiedAccount();
        String token = requestResetToken(email);

        List<CompletableFuture<ApiClient.Response>> requests = List.of(
                CompletableFuture.supplyAsync(() -> accounts.client().post(RESET, body("token", token, "newPassword", "concorrente-1-abc"))),
                CompletableFuture.supplyAsync(() -> accounts.client().post(RESET, body("token", token, "newPassword", "concorrente-2-abc"))),
                CompletableFuture.supplyAsync(() -> accounts.client().post(RESET, body("token", token, "newPassword", "concorrente-3-abc"))));

        List<Integer> statuses = requests.stream().map(request -> request.join().status()).toList();

        assertThat(statuses).containsExactlyInAnyOrder(204, 400, 400);
    }

    @Test
    void expiredResetTokenIsRejected() {
        String email = verifiedAccount();
        String token = requestResetToken(email);
        jdbc.update("""
                UPDATE password_reset_tokens SET expires_at = now() - interval '1 second'
                WHERE user_id = (SELECT id FROM users WHERE email = ?)
                """, email);

        ApiClient.Response response = accounts.client().post(RESET, body("token", token, "newPassword", NEW_PASSWORD));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void newResetRequestInvalidatesThePreviousToken() {
        String email = verifiedAccount();
        String first = requestResetToken(email);
        String second = requestResetToken(email);

        assertThat(accounts.client().post(RESET, body("token", first, "newPassword", NEW_PASSWORD)).code())
                .isEqualTo("INVALID_TOKEN");
        assertThat(accounts.client().post(RESET, body("token", second, "newPassword", NEW_PASSWORD)).status())
                .isEqualTo(204);
    }

    @Test
    void tokensOfOneKindDoNotWorkForTheOther() {
        String email = uniqueEmail();
        accounts.registerUnverified(email);
        String verificationToken = mail.awaitToken(email);

        assertThat(accounts.client().post(RESET, body("token", verificationToken, "newPassword", NEW_PASSWORD)).code())
                .isEqualTo("INVALID_TOKEN");
    }

    @Test
    void recoveryTakesOverAPreRegisteredAccountFromWhoeverCreatedIt() {
        String email = uniqueEmail();
        accounts.registerUnverified(email);
        mail.awaitMessage(email);
        String token = requestResetToken(email);

        assertThat(accounts.client().post(RESET, body("token", token, "newPassword", NEW_PASSWORD)).status())
                .isEqualTo(204);

        assertThat(accounts.client().post(LOGIN, body("email", email, "password", PASSWORD)).code())
                .isEqualTo("INVALID_CREDENTIALS");
        assertThat(accounts.client().post(LOGIN, body("email", email, "password", NEW_PASSWORD)).status()).isEqualTo(200);
    }

    @Test
    void changingPasswordRequiresTheCurrentOne() {
        String email = verifiedAccount();
        ApiClient session = accounts.loggedIn(email, PASSWORD);

        ApiClient.Response wrong = session.put(CHANGE, body("currentPassword", "errada-123", "newPassword", NEW_PASSWORD));

        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.code()).isEqualTo("INVALID_CURRENT_PASSWORD");
        assertThat(accounts.client().post(LOGIN, body("email", email, "password", PASSWORD)).status()).isEqualTo(200);
    }

    @Test
    void changingPasswordKeepsThisSessionAndRevokesTheOthers() {
        String email = verifiedAccount();
        ApiClient current = accounts.loggedIn(email, PASSWORD);
        ApiClient other = accounts.loggedIn(email, PASSWORD);
        String sessionBefore = current.cookie("SESSION").orElseThrow();

        ApiClient.Response response = current.put(CHANGE, body("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD));

        assertThat(response.status()).isEqualTo(204);
        assertThat(current.get(PRIVATE).status()).isEqualTo(200);
        assertThat(current.cookie("SESSION").orElseThrow()).isNotEqualTo(sessionBefore);
        assertThat(other.get(PRIVATE).status()).isEqualTo(401);
        assertThat(accounts.client().post(LOGIN, body("email", email, "password", NEW_PASSWORD)).status()).isEqualTo(200);
    }

    @Test
    void changingPasswordInvalidatesPendingResetTokens() {
        String email = verifiedAccount();
        String token = requestResetToken(email);
        ApiClient session = accounts.loggedIn(email, PASSWORD);

        session.put(CHANGE, body("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD));

        assertThat(accounts.client().post(RESET, body("token", token, "newPassword", "tentativa-000")).code())
                .isEqualTo("INVALID_TOKEN");
    }

    @Test
    void changingPasswordRequiresAuthentication() {
        ApiClient.Response response = accounts.client()
                .put(CHANGE, body("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void recoveryEmailsAreLimitedPerAddress() {
        String email = uniqueEmail();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(accounts.client().post(FORGOT, body("email", email)).status()).isEqualTo(202);
        }

        assertThat(accounts.client().post(FORGOT, body("email", email)).status()).isEqualTo(429);
    }
}
