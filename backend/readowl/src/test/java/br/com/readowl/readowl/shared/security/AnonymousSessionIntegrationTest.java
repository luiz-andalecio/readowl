package br.com.readowl.readowl.shared.security;

import br.com.readowl.readowl.support.ApiClient;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class AnonymousSessionIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/api/v1/qualquer", "/api/v1/auth/session"})
    void anonymousRequestsCreateNoSessionAndSetNoCookies(String path) {
        Integer before = sessionCount();

        ApiClient.Response response = new ApiClient(port).get(path);

        assertThat(response.setCookies()).isEmpty();
        assertThat(sessionCount()).isEqualTo(before);
    }

    private Integer sessionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class);
    }
}
