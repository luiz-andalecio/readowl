package br.com.readowl.readowl.shared.security;

import br.com.readowl.readowl.support.AccountFixtures;
import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiTestConfiguration;
import br.com.readowl.readowl.support.RecordingMailSender;
import br.com.readowl.readowl.support.TestDatabaseConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.multipart.MultipartResolver;

import java.util.List;
import java.util.UUID;

import static br.com.readowl.readowl.support.AccountFixtures.PASSWORD;
import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Perfil de produção com a aplicação atrás do proxy, papel que o cliente de teste faz ao conectar de localhost.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"TRUSTED_PROXIES=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1", "logging.structured.format.console=",
                "spring.flyway.enabled=true", "management.server.port=0"})
@ActiveProfiles({"test", "prod"})
@Import({TestDatabaseConfiguration.class, ApiTestConfiguration.class})
class TrustedProxyIntegrationTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String FORGOT = "/api/v1/auth/password/forgot";
    private static final String WRONG = "errada-123";

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    private AccountFixtures accounts;

    @BeforeEach
    void setUp() {
        accounts = new AccountFixtures(port, mail, jdbc);
        accounts.resetRateLimits();
        mail.setFailing(false);
    }

    private ApiClient from(String clientAddress) {
        return accounts.client().withHeader("X-Forwarded-For", clientAddress);
    }

    private boolean countedFor(String policy, String subject) {
        return jdbc.queryForObject("SELECT count(*) FROM rate_limit_buckets WHERE bucket_key = ?", Integer.class,
                policy + ":" + Hashing.sha256Hex(subject)) > 0;
    }

    private String verifiedAccount() {
        String email = uniqueEmail();
        accounts.registerVerified(email);
        accounts.resetRateLimits();
        return email;
    }

    @Test
    void theClientIsTheLastAddressThatIsNotTheProxy() {
        from("1.2.3.4, 198.51.100.7").post(LOGIN, body("email", uniqueEmail(), "password", WRONG));

        assertThat(countedFor("LOGIN_IP", "198.51.100.7")).isTrue();
        assertThat(countedFor("LOGIN_IP", "1.2.3.4")).isFalse();
    }

    @Test
    void theProtocolInformedByTheProxyIsUsed() {
        ApiClient.Response csrf = accounts.client().withHeader("X-Forwarded-Proto", "https").get("/api/v1/auth/csrf");

        assertThat(csrf.setCookie("__Host-XSRF-TOKEN").orElseThrow()).contains("Secure").contains("Path=/");
    }

    @Test
    void aForgedPrefixDoesNotEscapeTheAudit() {
        String email = verifiedAccount();
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);
        UUID admin = accounts.userId(email);
        ApiClient client = accounts.loggedIn(email, PASSWORD).withHeader("X-Forwarded-Prefix", "/x");

        assertThat(client.post("/api/v1/admin/tags", body("name", "Tag " + UUID.randomUUID())).status()).isEqualTo(201);

        assertThat(jdbc.queryForList("SELECT resource FROM audit_log WHERE actor_id = ?", String.class, admin))
                .containsExactly("/api/v1/admin/tags");
    }

    @Test
    void wrongPasswordsFromOneAddressDoNotKeepTheOwnerOut() {
        String email = verifiedAccount();
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(from("203.0.113.1").post(LOGIN, body("email", email, "password", WRONG)).status()).isEqualTo(401);
        }
        assertThat(from("203.0.113.1").post(LOGIN, body("email", email, "password", WRONG)).status()).isEqualTo(429);

        assertThat(from("198.51.100.7").post(LOGIN, body("email", email, "password", PASSWORD)).status()).isEqualTo(200);
    }

    @Test
    void wrongPasswordsFromManyAddressesAreLimitedForTheWholeAccount() {
        String email = verifiedAccount();
        for (int address = 1; address <= 10; address++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                assertThat(from("203.0.113." + address).post(LOGIN, body("email", email, "password", WRONG)).status())
                        .isEqualTo(401);
            }
        }

        assertThat(from("203.0.113.11").post(LOGIN, body("email", email, "password", WRONG)).status()).isEqualTo(429);
    }

    @Test
    void aBrowserThatAlreadyLoggedInStillGetsInDuringADistributedAttack() {
        String email = verifiedAccount();
        ApiClient owner = from("198.51.100.7");
        ApiClient.Response first = owner.post(LOGIN, body("email", email, "password", PASSWORD));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.setCookie("READOWL_DEVICE").orElseThrow())
                .contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/v1/auth/login");
        for (int address = 1; address <= 10; address++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                from("203.0.113." + address).post(LOGIN, body("email", email, "password", WRONG));
            }
        }

        assertThat(from("203.0.113.11").post(LOGIN, body("email", email, "password", PASSWORD)).status()).isEqualTo(429);

        ApiClient.Response again = owner.post(LOGIN, body("email", email, "password", PASSWORD));
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.setCookie("READOWL_DEVICE")).as("o navegador já era conhecido").isEmpty();
    }

    @Test
    void anotherAccountsDeviceCookieDoesNotHelp() {
        String attacker = verifiedAccount();
        String victim = verifiedAccount();
        ApiClient client = from("203.0.113.50");
        assertThat(client.post(LOGIN, body("email", attacker, "password", PASSWORD)).status()).isEqualTo(200);
        for (int address = 1; address <= 10; address++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                from("203.0.113." + address).post(LOGIN, body("email", victim, "password", WRONG));
            }
        }

        assertThat(client.post(LOGIN, body("email", victim, "password", WRONG)).status()).isEqualTo(429);
    }

    @Test
    void recoveringThePasswordEndsTheBlockAndForgetsKnownBrowsers() {
        String email = verifiedAccount();
        ApiClient owner = from("198.51.100.7");
        assertThat(owner.post(LOGIN, body("email", email, "password", PASSWORD)).status()).isEqualTo(200);
        for (int address = 1; address <= 10; address++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                from("203.0.113." + address).post(LOGIN, body("email", email, "password", WRONG));
            }
        }
        assertThat(from("198.51.100.8").post(FORGOT, body("email", email)).status()).isEqualTo(202);

        assertThat(from("198.51.100.8").post("/api/v1/auth/password/reset",
                body("token", mail.awaitToken(email), "newPassword", "outra-senha-forte")).status()).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM known_devices kd JOIN users u ON u.id = kd.user_id WHERE u.email = ?",
                Integer.class, email)).isZero();
        assertThat(from("198.51.100.9").post(LOGIN, body("email", email, "password", "outra-senha-forte")).status()).isEqualTo(200);
    }

    @Test
    void recoveryRequestsFromOneAddressDoNotBlockTheOwner() {
        String email = verifiedAccount();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(from("203.0.113.1").post(FORGOT, body("email", email)).status()).isEqualTo(202);
        }
        assertThat(from("203.0.113.1").post(FORGOT, body("email", email)).status()).isEqualTo(429);

        assertThat(from("198.51.100.7").post(FORGOT, body("email", email)).status()).isEqualTo(202);
    }

    @Test
    void recoveryEmailsToOneAddressHaveAnOverallLimit() {
        String email = verifiedAccount();
        List<String> addresses = List.of("203.0.113.1", "203.0.113.2", "203.0.113.3", "203.0.113.4");
        int accepted = 0;
        for (String address : addresses) {
            for (int attempt = 0; attempt < 3; attempt++) {
                if (from(address).post(FORGOT, body("email", email)).status() == 202) {
                    accepted++;
                }
            }
        }

        assertThat(accepted).isEqualTo(10);
    }

    @Test
    void browsersAreToldToKeepUsingHttps() {
        ApiClient.Response viaHttps = accounts.client().withHeader("X-Forwarded-Proto", "https").get("/api/v1/books");

        assertThat(viaHttps.headers().firstValue("Strict-Transport-Security")).isPresent();
        assertThat(accounts.client().get("/api/v1/books").headers().firstValue("Strict-Transport-Security")).isEmpty();
    }

    @Test
    void thePublicPortOnlyShowsProbesAndNoDetails() {
        ApiClient visitor = accounts.client();

        ApiClient.Response ready = visitor.get("/readyz");
        assertThat(ready.status()).isEqualTo(200);
        assertThat(ready.json().has("components")).isFalse();
        assertThat(visitor.get("/livez").status()).isEqualTo(200);
        for (String endpoint : List.of("health", "metrics", "env", "beans", "configprops", "loggers", "heapdump", "mappings")) {
            assertThat(visitor.get("/actuator/" + endpoint).status()).as(endpoint).isEqualTo(401);
        }
    }

    @Test
    void theInternalPortOnlyAddsHealthAndMetrics() {
        ApiClient internal = new ApiClient(managementPort);

        assertThat(internal.get("/actuator/health/readiness").status()).isEqualTo(200);
        assertThat(internal.get("/actuator/health").json().has("components")).isFalse();
        assertThat(internal.get("/actuator/metrics/hikaricp.connections.pending").status()).isEqualTo(200);
        assertThat(internal.get("/actuator/metrics/readowl.account_mail.pending").status()).isEqualTo(200);
        for (String endpoint : List.of("env", "beans", "configprops", "loggers", "heapdump", "mappings")) {
            assertThat(internal.get("/actuator/" + endpoint).status()).as(endpoint).isEqualTo(401);
        }
    }

    @Test
    void theSessionCookieCannotBeReadByScripts() {
        String email = verifiedAccount();

        ApiClient.Response login = accounts.client().post(LOGIN, body("email", email, "password", PASSWORD));

        assertThat(login.setCookie("__Host-SESSION").orElseThrow()).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
        assertThat(login.setCookie("__Host-SESSION").orElseThrow()).doesNotContain("Domain=");
    }

    @Test
    void uploadsAreOffWhileNoRouteReceivesFiles() {
        assertThat(context.getBeanNamesForType(MultipartResolver.class)).isEmpty();
    }

    @Test
    void theSessionCookieUsesTheHostPrefix() {
        String email = verifiedAccount();
        ApiClient client = accounts.client();

        ApiClient.Response login = client.post(LOGIN, body("email", email, "password", PASSWORD));

        assertThat(login.setCookie("__Host-SESSION")).isPresent();
    }
}
