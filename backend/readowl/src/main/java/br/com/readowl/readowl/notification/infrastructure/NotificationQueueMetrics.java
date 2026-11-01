package br.com.readowl.readowl.notification.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Estado das filas de notificação, lido do banco a cada coleta. As contagens usam índices parciais ou tabelas que a
 * limpeza mantém pequenas.
 */
@Component
class NotificationQueueMetrics implements MeterBinder {

    private final JdbcTemplate jdbc;

    NotificationQueueMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "readowl.outbox.pending", "Eventos à espera do worker",
                "SELECT count(*) FROM outbox_events WHERE processed_at IS NULL AND failed_at IS NULL");
        gauge(registry, "readowl.outbox.failed", "Eventos que esgotaram as tentativas",
                "SELECT count(*) FROM outbox_events WHERE failed_at IS NOT NULL");
        gauge(registry, "readowl.outbox.delay.seconds", "Atraso do evento vencido há mais tempo", """
                SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(next_attempt_at)), 0)
                FROM outbox_events WHERE processed_at IS NULL AND failed_at IS NULL AND next_attempt_at < now()
                """);
        gauge(registry, "readowl.chapter_mail.pending", "E-mails de capítulo novo à espera do worker",
                "SELECT count(*) FROM notifications WHERE email_status = 'PENDING'");
    }

    private void gauge(MeterRegistry registry, String name, String description, String sql) {
        Gauge.builder(name, jdbc, template -> template.queryForObject(sql, Double.class))
                .description(description)
                .register(registry);
    }
}
