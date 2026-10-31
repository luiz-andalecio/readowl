package br.com.readowl.readowl.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static br.com.readowl.readowl.support.ApiClient.body;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cria contas passando pela API, como um usuário real.
 */
public final class AccountFixtures {

    public static final String PASSWORD = "senha-forte-123";

    private final int port;
    private final RecordingMailSender mail;
    private final JdbcTemplate jdbc;

    public AccountFixtures(int port, RecordingMailSender mail, JdbcTemplate jdbc) {
        this.port = port;
        this.mail = mail;
        this.jdbc = jdbc;
    }

    /**
     * Os limites contam por IP, e todos os testes usam o mesmo: cada teste começa do zero.
     */
    public void resetRateLimits() {
        jdbc.update("DELETE FROM rate_limit_buckets");
    }

    public ApiClient client() {
        return new ApiClient(port);
    }

    public static String uniqueEmail() {
        return unique() + "@example.com";
    }

    public static String unique() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static Map<String, Object> registration(String username, String email, String password) {
        return body("name", "Pessoa Leitora", "username", username, "email", email, "password", password);
    }

    public String registerUnverified(String email) {
        String username = unique();
        assertThat(client().post("/api/v1/auth/register", registration(username, email, PASSWORD)).status())
                .isEqualTo(202);
        return username;
    }

    public String registerVerified(String email) {
        String username = registerUnverified(email);
        assertThat(client().post("/api/v1/auth/verify-email", body("token", mail.awaitToken(email))).status())
                .isEqualTo(204);
        return username;
    }

    public ApiClient loggedIn(String email, String password) {
        ApiClient client = client();
        assertThat(client.post("/api/v1/auth/login", body("email", email, "password", password)).status())
                .isEqualTo(200);
        return client;
    }

    public UUID userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }
}
