package br.com.readowl.readowl.shared.persistence;

import br.com.readowl.readowl.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class DatabaseTimeoutIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionOperations transaction;

    @Test
    void connectionsHaveMaximumTimes() {
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("30s");
        assertThat(jdbc.queryForObject("SHOW idle_in_transaction_session_timeout", String.class)).isEqualTo("1min");
    }

    /**
     * O tratamento global responde 503 a este tipo de falha, para o cliente tentar de novo.
     */
    @Test
    void aQueryOverTheLimitIsInterruptedAsATemporaryFailure() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL statement_timeout = '50ms'");
            jdbc.execute("SELECT pg_sleep(1)");
        })).isInstanceOf(TransientDataAccessException.class);
    }
}
