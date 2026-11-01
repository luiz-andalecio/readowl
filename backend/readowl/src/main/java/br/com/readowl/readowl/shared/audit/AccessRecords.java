package br.com.readowl.readowl.shared.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * Registro de acesso: quem agiu, de qual IP e quando. Guarda o endereço completo, e não o agrupado dos limites por IP,
 * porque serve para identificar uma conexão. Nunca guarda conteúdo, senha ou token.
 */
@Component
public class AccessRecords {

    private static final Logger log = LoggerFactory.getLogger(AccessRecords.class);
    private static final int MAX_ACTION_LENGTH = 200;

    private final JdbcTemplate jdbc;

    AccessRecords(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A ação já aconteceu: uma falha aqui não a desfaz, mas precisa aparecer.
     */
    public void record(UUID userId, String action) {
        String stored = action.length() <= MAX_ACTION_LENGTH ? action : action.substring(0, MAX_ACTION_LENGTH);
        try {
            jdbc.update("INSERT INTO access_records (id, user_id, ip, action, created_at) VALUES (uuidv7(), ?, ?, ?, now())",
                    userId, currentAddress(), stored);
        } catch (RuntimeException failure) {
            log.error("Falha ao gravar o registro de acesso: acao={}", stored, failure);
        }
    }

    private static String currentAddress() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getRemoteAddr()
                : null;
    }
}
