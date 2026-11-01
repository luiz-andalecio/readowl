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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static br.com.readowl.readowl.support.AccountFixtures.uniqueEmail;
import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Perfil de produção recebendo conexões de fora do proxy: os cabeçalhos do cliente não valem nada.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"TRUSTED_PROXIES=10\\.255\\.255\\.1", "logging.structured.format.console="})
@ActiveProfiles({"test", "prod"})
@Import({TestDatabaseConfiguration.class, ApiTestConfiguration.class})
class UntrustedProxyIntegrationTest {

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
    }

    @Test
    void aForgedAddressIsIgnored() {
        ApiClient client = accounts.client().withHeader("X-Forwarded-For", "1.2.3.4");

        client.post("/api/v1/auth/login", body("email", uniqueEmail(), "password", "errada-123"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM rate_limit_buckets WHERE bucket_key = ?", Integer.class,
                "LOGIN_IP:" + Hashing.sha256Hex("1.2.3.4"))).isZero();
    }

    @Test
    void aForgedProtocolIsIgnored() {
        ApiClient.Response csrf = accounts.client().withHeader("X-Forwarded-Proto", "https").get("/api/v1/auth/csrf");

        assertThat(csrf.setCookie("XSRF-TOKEN").orElseThrow()).doesNotContain("Secure");
    }
}
