package br.com.readowl.readowl.auth.infrastructure;

import br.com.readowl.readowl.auth.application.AccountMailType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reservar um e-mail adia a próxima tentativa, em um único comando: ninguém mais o pega durante o envio, que
 * acontece fora de transação. Se a aplicação cair no meio dele, o prazo vence e outra instância o assume.
 */
@Repository
public class AccountMailQueue {

    private static final RowMapper<PendingAccountMail> ROW = (rs, row) -> new PendingAccountMail(
            rs.getObject("id", UUID.class), AccountMailType.valueOf(rs.getString("type")), rs.getString("email"));

    private final NamedParameterJdbcTemplate jdbc;

    AccountMailQueue(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PendingAccountMail(UUID id, AccountMailType type, String email) {

        // O endereço não vai para logs.
        @Override
        public String toString() {
            return "PendingAccountMail[id=" + id + ", type=" + type + "]";
        }
    }

    public UUID enqueue(AccountMailType type, String email) {
        return jdbc.queryForObject("""
                INSERT INTO account_mail_queue (id, type, email, created_at, next_attempt_at)
                VALUES (uuidv7(), :type, :email, now(), now())
                RETURNING id
                """, Map.of("type", type.name(), "email", email), UUID.class);
    }

    public Optional<PendingAccountMail> claim(UUID id) {
        return jdbc.query("""
                UPDATE account_mail_queue SET next_attempt_at = now() + interval '5 minutes'
                WHERE id = :id AND failed_at IS NULL AND next_attempt_at <= now()
                RETURNING id, type, email
                """, Map.of("id", id), ROW).stream().findFirst();
    }

    public List<PendingAccountMail> claimDue(int limit) {
        return jdbc.query("""
                WITH due AS (
                    SELECT id FROM account_mail_queue
                    WHERE failed_at IS NULL AND next_attempt_at <= now()
                    ORDER BY next_attempt_at, id LIMIT :limit FOR UPDATE SKIP LOCKED
                )
                UPDATE account_mail_queue q SET next_attempt_at = now() + interval '5 minutes'
                FROM due WHERE q.id = due.id
                RETURNING q.id, q.type, q.email
                """, Map.of("limit", limit), ROW);
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM account_mail_queue WHERE id = :id", Map.of("id", id));
    }

    public void recordFailure(UUID id, String error, int maxAttempts) {
        jdbc.update("""
                UPDATE account_mail_queue
                SET attempts = attempts + 1,
                    last_error = :error,
                    next_attempt_at = now() + (attempts + 1) * interval '1 minute',
                    failed_at = CASE WHEN attempts + 1 >= :maxAttempts THEN now() END
                WHERE id = :id
                """, Map.of("id", id, "error", error, "maxAttempts", maxAttempts));
    }

    /**
     * O endereço é dado pessoal: quem falhou de vez fica só o tempo de investigar.
     */
    public int purgeFailed() {
        return jdbc.update("DELETE FROM account_mail_queue WHERE failed_at < now() - interval '7 days'", Map.of());
    }
}
