package br.com.readowl.readowl.auth.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Estado da fila de e-mails de conta, lido do banco a cada coleta. Fila parada significa que ninguém consegue
 * confirmar o cadastro ou recuperar a senha.
 */
@Component
class AccountMailQueueMetrics implements MeterBinder {

    private final JdbcTemplate jdbc;

    AccountMailQueueMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "readowl.account_mail.pending", "E-mails de conta ainda não entregues",
                "SELECT count(*) FROM account_mail_queue WHERE failed_at IS NULL");
        gauge(registry, "readowl.account_mail.failed", "E-mails de conta que esgotaram as tentativas",
                "SELECT count(*) FROM account_mail_queue WHERE failed_at IS NOT NULL");
        gauge(registry, "readowl.account_mail.delay.seconds", "Atraso do e-mail vencido há mais tempo", """
                SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(next_attempt_at)), 0)
                FROM account_mail_queue WHERE failed_at IS NULL AND next_attempt_at < now()
                """);
    }

    private void gauge(MeterRegistry registry, String name, String description, String sql) {
        Gauge.builder(name, jdbc, template -> template.queryForObject(sql, Double.class))
                .description(description)
                .register(registry);
    }
}
