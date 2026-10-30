package br.com.readowl.readowl.shared.security;

import br.com.readowl.readowl.support.TestDatabaseConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Usa servidor HTTP real: o MockMvc com spring-security-test cria sessões próprias e mascararia este comportamento.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class AnonymousSessionIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/api/v1/qualquer"})
    void anonymousRequestsDoNotCreateSessions(String path) throws Exception {
        Integer before = sessionCount();

        HttpResponse<Void> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
            response = client.send(request, HttpResponse.BodyHandlers.discarding());
        }

        assertThat(response.headers().allValues("Set-Cookie")).noneMatch(cookie -> cookie.startsWith("SESSION"));
        assertThat(sessionCount()).isEqualTo(before);
    }

    private Integer sessionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class);
    }
}
