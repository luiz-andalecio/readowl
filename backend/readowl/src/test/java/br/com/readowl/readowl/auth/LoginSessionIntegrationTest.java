package br.com.readowl.readowl.auth;

import br.com.readowl.readowl.auth.application.SessionManager;
import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.RecordingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.unique;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LoginSessionIntegrationTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String SESSION = "/api/v1/auth/session";
    private static final String PRIVATE = "/api/v1/users/me";

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionManager sessions;

    private AccountFixtures accounts;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        mail.setFailing(false);
    }

    @Test
    void loginCreatesSessionWithIdRatherThanEntityData() {
        String email = uniqueEmail();
        String username = accounts.registerVerified(email);
        ApiClient client = accounts.client();

        ApiClient.Response response = client.post(LOGIN, body("email", email.toUpperCase(), "password", PASSWORD));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().at("/authenticated").asBoolean()).isTrue();
        assertThat(response.json().at("/user/username").asString()).isEqualTo(username);
        assertThat(response.json().at("/user/role").asString()).isEqualTo("USER");
        assertThat(response.json().at("/user/hasPassword").asBoolean()).isTrue();
        assertThat(response.json().at("/user/googleLinked").asBoolean()).isFalse();
        assertThat(response.body()).doesNotContain(email).doesNotContain("passwordHash");

        assertThat(jdbc.queryForObject("SELECT principal_name FROM spring_session WHERE principal_name = ?",
                String.class, accounts.userId(email).toString())).isEqualTo(accounts.userId(email).toString());
        assertThat(client.get(SESSION).json().at("/authenticated").asBoolean()).isTrue();
    }

    @Test
    void sessionCookieIsProtectedAndCsrfCookieIsReadableWithSameSite() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient client = accounts.client();

        ApiClient.Response login = client.post(LOGIN, body("email", email, "password", PASSWORD));

        assertThat(login.setCookie("SESSION").orElseThrow())
                .contains("HttpOnly").contains("SameSite=Lax").contains("Path=/").doesNotContain("Max-Age");

        String csrfCookie = client.get("/api/v1/auth/csrf").setCookie("XSRF-TOKEN").orElseThrow();
        assertThat(csrfCookie).contains("SameSite=Lax").doesNotContain("HttpOnly");
    }

    @Test
    void wrongPasswordUnknownAccountAndUnverifiedWithWrongPasswordGiveTheSameAnswer() {
        String verified = uniqueEmail();
        accounts.registerVerified(verified);
        String unverified = uniqueEmail();
        accounts.registerUnverified(unverified);

        ApiClient.Response wrongPassword = accounts.client().post(LOGIN, body("email", verified, "password", "errada-123"));
        ApiClient.Response unknown = accounts.client().post(LOGIN, body("email", uniqueEmail(), "password", "errada-123"));
        ApiClient.Response wrongUnverified = accounts.client().post(LOGIN, body("email", unverified, "password", "errada-123"));

        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(wrongPassword.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(unknown.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(unknown.json().path("detail").asString()).isEqualTo(wrongPassword.json().path("detail").asString());
        assertThat(wrongUnverified.code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void unverifiedAndSuspendedAccountsAreRefusedOnlyAfterTheCorrectPassword() {
        String unverified = uniqueEmail();
        accounts.registerUnverified(unverified);
        String suspended = uniqueEmail();
        accounts.registerVerified(suspended);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", suspended);

        ApiClient.Response notVerified = accounts.client().post(LOGIN, body("email", unverified, "password", PASSWORD));
        ApiClient.Response notActive = accounts.client().post(LOGIN, body("email", suspended, "password", PASSWORD));

        assertThat(notVerified.status()).isEqualTo(403);
        assertThat(notVerified.code()).isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(notActive.status()).isEqualTo(403);
        assertThat(notActive.code()).isEqualTo("ACCOUNT_SUSPENDED");
    }

    @Test
    void loginAndLogoutRequireCsrfToken() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient client = accounts.client();

        ApiClient.Response login = client.postWithoutCsrf(LOGIN, body("email", email, "password", PASSWORD));
        assertThat(login.status()).isEqualTo(403);
        assertThat(login.code()).isEqualTo("CSRF_TOKEN_INVALID");

        ApiClient loggedIn = accounts.loggedIn(email, PASSWORD);
        assertThat(loggedIn.postWithoutCsrf(LOGOUT, body()).status()).isEqualTo(403);
        assertThat(loggedIn.get(SESSION).json().at("/authenticated").asBoolean()).isTrue();
    }

    @Test
    void csrfTokenFromBeforeLoginIsDiscarded() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient client = accounts.client();
        client.get("/api/v1/auth/csrf");
        String tokenBeforeLogin = client.cookie("XSRF-TOKEN").orElseThrow();

        ApiClient.Response login = client.postWithCsrfHeader(LOGIN, body("email", email, "password", PASSWORD), tokenBeforeLogin);

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.setCookie("XSRF-TOKEN").orElseThrow()).startsWith("XSRF-TOKEN=;").contains("1970");
        assertThat(client.postWithCsrfHeader(LOGOUT, body(), tokenBeforeLogin).code()).isEqualTo("CSRF_TOKEN_INVALID");
    }

    @Test
    void logoutInvalidatesTheSessionEvenIfTheCookieIsReused() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient client = accounts.loggedIn(email, PASSWORD);
        String sessionCookie = client.cookie("SESSION").orElseThrow();
        assertThat(client.get(PRIVATE).status()).isEqualTo(200);

        ApiClient.Response logout = client.post(LOGOUT, body());
        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.setCookie("XSRF-TOKEN").orElseThrow()).startsWith("XSRF-TOKEN=;").contains("1970");

        ApiClient thief = accounts.client();
        thief.useSessionCookie(sessionCookie);
        assertThat(thief.get(PRIVATE).status()).isEqualTo(401);
        assertThat(client.get(SESSION).json().at("/authenticated").asBoolean()).isFalse();
    }

    @Test
    void loggingInAgainRotatesTheSessionId() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient client = accounts.loggedIn(email, PASSWORD);
        String firstSession = client.cookie("SESSION").orElseThrow();

        assertThat(client.post(LOGIN, body("email", email, "password", PASSWORD)).status()).isEqualTo(200);

        assertThat(client.cookie("SESSION").orElseThrow()).isNotEqualTo(firstSession);
        ApiClient oldSession = accounts.client();
        oldSession.useSessionCookie(firstSession);
        assertThat(oldSession.get(PRIVATE).status()).isEqualTo(401);
    }

    @Test
    void revokingSessionsLogsTheUserOutEverywhere() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        ApiClient phone = accounts.loggedIn(email, PASSWORD);
        ApiClient laptop = accounts.loggedIn(email, PASSWORD);
        UUID userId = accounts.userId(email);

        sessions.revokeAll(userId);

        assertThat(phone.get(PRIVATE).status()).isEqualTo(401);
        assertThat(laptop.get(PRIVATE).status()).isEqualTo(401);
    }

    @Test
    void publicEndpointsSetNoCookies() {
        String username = accounts.registerVerified(uniqueEmail());

        assertThat(accounts.client().get(SESSION).setCookies()).isEmpty();
        assertThat(accounts.client().get("/api/v1/users/" + username).setCookies()).isEmpty();
        assertThat(accounts.client().get("/actuator/health").setCookies()).isEmpty();
    }

    @Test
    void anonymousSessionEndpointReportsNoUser() {
        ApiClient.Response response = accounts.client().get(SESSION);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().at("/authenticated").asBoolean()).isFalse();
        assertThat(response.json().at("/user").isNull()).isTrue();
    }

    @Test
    void aSuccessfulLoginClearsTheFailedAttempts() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        for (int round = 0; round < 2; round++) {
            for (int attempt = 0; attempt < 9; attempt++) {
                assertThat(accounts.client().post(LOGIN, body("email", email, "password", "errada-123")).status())
                        .isEqualTo(401);
            }
            assertThat(accounts.client().post(LOGIN, body("email", email, "password", PASSWORD)).status()).isEqualTo(200);
        }
    }

    @Test
    void loginIsLimitedPerAccountEvenForUnknownEmails() {
        String email = uniqueEmail();
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(accounts.client().post(LOGIN, body("email", email, "password", "errada-123")).status())
                    .isEqualTo(401);
        }

        ApiClient.Response limited = accounts.client().post(LOGIN, body("email", email.toUpperCase(), "password", "errada-123"));

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void loginIsLimitedPerIpAcrossAccounts() {
        for (int attempt = 0; attempt < 30; attempt++) {
            assertThat(accounts.client().post(LOGIN, body("email", uniqueEmail(), "password", "errada-123")).status())
                    .isEqualTo(401);
        }

        assertThat(accounts.client().post(LOGIN, body("email", uniqueEmail(), "password", "errada-123")).status())
                .isEqualTo(429);
    }

    @Test
    void rateLimitCountersDoNotStoreEmailsOrIps() {
        String email = uniqueEmail();
        accounts.client().post(LOGIN, body("email", email, "password", "errada-123"));

        assertThat(jdbc.queryForList("SELECT bucket_key FROM rate_limit_buckets", String.class))
                .isNotEmpty()
                .allSatisfy(key -> assertThat(key).doesNotContain(email).doesNotContain("127.0.0.1"));
    }

    @Test
    void credentialsAndTokensNeverReachTheLogs(CapturedOutput output) {
        String email = uniqueEmail();
        String password = "senha-rastreavel-" + unique();
        accounts.client().post("/api/v1/auth/register", AccountFixtures.registration(unique(), email, password));
        String token = mail.awaitToken(email);
        accounts.client().post("/api/v1/auth/verify-email", body("token", token));
        ApiClient client = accounts.loggedIn(email, password);
        String sessionCookie = client.cookie("SESSION").orElseThrow();
        accounts.client().post(LOGIN, body("email", email, "password", "tentativa-errada-" + password));

        assertThat(output.getAll()).doesNotContain(password).doesNotContain(token).doesNotContain(sessionCookie);
    }
}
