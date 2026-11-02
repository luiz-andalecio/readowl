package br.com.readowl.readowl.privacy.application;

import br.com.readowl.readowl.community.domain.Comment;
import br.com.readowl.readowl.shared.config.ReadowlProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Remove o que passou do prazo. Cada etapa é independente: a falha de uma não impede as outras, e tudo é seguro para
 * repetir ou rodar em várias instâncias. Os prazos estão em docs/personal-data.md.
 */
@Component
public class RetentionTasks {

    private static final Logger log = LoggerFactory.getLogger(RetentionTasks.class);
    private static final int ACCOUNT_BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final RetainedBooks books;
    private final ReadowlProperties.Retention retention;

    RetentionTasks(JdbcTemplate jdbc, RetainedBooks books, ReadowlProperties properties) {
        this.jdbc = jdbc;
        this.books = books;
        this.retention = properties.retention();
    }

    @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT1H")
    public void run() {
        step("contas nao confirmadas", this::purgeUnverifiedAccounts);
        step("notificacoes", () -> jdbc.update(
                "DELETE FROM notifications WHERE created_at < now() - make_interval(secs => ?)", seconds(retention.notifications())));
        step("texto de comentarios excluidos", () -> jdbc.update("""
                UPDATE comments c SET content = ?, image_url = NULL, content_purged_at = now()
                WHERE c.deleted_at < now() - make_interval(secs => ?) AND c.content_purged_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM reports r
                                  WHERE r.status = 'OPEN' AND r.target_type = 'COMMENT' AND r.target_id = c.id)
                """, Comment.PURGED_CONTENT, seconds(retention.deletedCommentText())));
        step("denuncias encerradas", () -> jdbc.update(
                "DELETE FROM reports WHERE status <> 'OPEN' AND resolved_at < now() - make_interval(secs => ?)",
                seconds(retention.closedReports())));
        step("obras de contas excluidas", books::deleteReleased);
        step("auditoria", () -> purgeWith("purge_audit_log", retention.auditLog()));
        step("registros de acesso", () -> purgeWith("purge_access_records", retention.accessRecords()));
    }

    /**
     * O usuário da aplicação não apaga essas tabelas. As funções rodam com o dono do schema e recusam prazos curtos.
     */
    private int purgeWith(String function, Duration period) {
        Long removed = jdbc.queryForObject("SELECT " + function + "(make_interval(secs => ?))", Long.class, seconds(period));
        return removed == null ? 0 : removed.intValue();
    }

    /**
     * Essas contas não entram, então não deveriam ter nada além de tokens. Uma por vez, para que uma exceção, como
     * uma conta alterada à mão no banco, não impeça as demais.
     */
    private int purgeUnverifiedAccounts() {
        List<UUID> expired = jdbc.queryForList("""
                SELECT id FROM users
                WHERE email_verified_at IS NULL AND status = 'ACTIVE' AND role = 'USER'
                  AND created_at < now() - make_interval(secs => ?)
                ORDER BY created_at LIMIT ?
                """, UUID.class, seconds(retention.unverifiedAccounts()), ACCOUNT_BATCH_SIZE);
        int removed = 0;
        for (UUID id : expired) {
            try {
                jdbc.update("DELETE FROM account_mail_queue WHERE email = (SELECT email FROM users WHERE id = ?)", id);
                removed += jdbc.update("DELETE FROM users WHERE id = ?", id);
            } catch (DataIntegrityViolationException ex) {
                log.warn("Conta nao confirmada com dados ligados a ela: conta={}", id);
            }
        }
        return removed;
    }

    private static void step(String name, Supplier<Integer> purge) {
        try {
            int removed = purge.get();
            if (removed > 0) {
                log.info("Retencao: etapa={}, linhas={}", name, removed);
            }
        } catch (DataAccessException failure) {
            log.error("Falha na retencao: etapa={}", name, failure);
        }
    }

    private static double seconds(Duration duration) {
        return duration.toSeconds();
    }
}
