package br.com.readowl.readowl.shared.audit;

import br.com.readowl.readowl.shared.security.Hashing;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Eventos de segurança: um log estruturado, uma métrica para alertas e, com a conta identificada, o registro de
 * acesso. Sem senha, token ou e-mail: a conta aparece pelo ID e, numa falha de login, por um hash do que foi digitado.
 */
@Component
public class SecurityEvents {

    public enum Event {
        LOGIN_SUCCEEDED,
        LOGIN_FAILED,
        GOOGLE_LOGIN_SUCCEEDED,
        GOOGLE_LINKED,
        PASSWORD_CHANGED,
        PASSWORD_RESET,
        EMAIL_CHANGE_REQUESTED,
        EMAIL_CHANGED,
        SESSION_REVOKED,
        TERMS_ACCEPTED,
        DATA_EXPORTED,
        ACCOUNT_DELETED
    }

    private static final Logger log = LoggerFactory.getLogger(SecurityEvents.class);
    private static final int HASH_PREFIX_LENGTH = 16;

    private final AccessRecords records;
    private final MeterRegistry meters;

    SecurityEvents(AccessRecords records, MeterRegistry meters) {
        this.records = records;
        this.meters = meters;
    }

    public void record(Event event, UUID userId) {
        count(event);
        log.info("Evento de seguranca: evento={}, conta={}", event, userId);
        records.record(userId, event.name());
    }

    /**
     * Sem conta confirmada: o hash permite ver um ataque concentrado em um alvo sem guardar o e-mail.
     */
    public void recordFailure(Event event, String attemptedAccount) {
        count(event);
        log.info("Evento de seguranca: evento={}, tentativa={}", event,
                Hashing.sha256Hex(attemptedAccount).substring(0, HASH_PREFIX_LENGTH));
    }

    private void count(Event event) {
        meters.counter("readowl.security.events", "event", event.name()).increment();
    }
}
