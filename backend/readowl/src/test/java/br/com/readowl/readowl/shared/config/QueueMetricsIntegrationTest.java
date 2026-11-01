package br.com.readowl.readowl.shared.config;

import br.com.readowl.readowl.support.ApiIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ApiIntegrationTest
class QueueMetricsIntegrationTest {

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyTheQueue() {
        jdbc.update("DELETE FROM account_mail_queue");
    }

    private double value(String name) {
        return registry.get(name).gauge().value();
    }

    private void queueMail(String nextAttempt, String failedAt) {
        jdbc.update("INSERT INTO account_mail_queue (id, type, email, created_at, next_attempt_at, failed_at) "
                + "VALUES (?, 'VERIFY_EMAIL', 'a@example.com', now(), " + nextAttempt + ", " + failedAt + ")",
                UUID.randomUUID());
    }

    @Test
    void anEmptyAccountMailQueueHasNoDelay() {
        assertThat(value("readowl.account_mail.pending")).isZero();
        assertThat(value("readowl.account_mail.failed")).isZero();
        assertThat(value("readowl.account_mail.delay.seconds")).isZero();
    }

    @Test
    void theAccountMailQueueShowsPendingFailedAndTheOldestDelay() {
        queueMail("now() - interval '10 minutes'", "NULL");
        queueMail("now() + interval '1 hour'", "NULL");
        queueMail("now() - interval '2 days'", "now()");

        assertThat(value("readowl.account_mail.pending")).isEqualTo(2);
        assertThat(value("readowl.account_mail.failed")).isEqualTo(1);
        assertThat(value("readowl.account_mail.delay.seconds")).isBetween(590.0, 700.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"readowl.outbox.pending", "readowl.outbox.failed", "readowl.outbox.delay.seconds",
            "readowl.chapter_mail.pending"})
    void theNotificationQueuesCanBeRead(String name) {
        assertThat(value(name)).isNotNaN().isNotNegative();
    }

    @ParameterizedTest
    @ValueSource(strings = {"hikaricp.connections.active", "hikaricp.connections.pending", "jvm.memory.used"})
    void theFrameworkMetricsNeededForAlertsExist(String name) {
        assertThat(registry.find(name).meter()).isNotNull();
    }
}
