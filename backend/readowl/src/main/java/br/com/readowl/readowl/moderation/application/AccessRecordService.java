package br.com.readowl.readowl.moderation.application;

import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.audit.AuditContext;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Consulta do registro de acesso, para responder a uma ordem judicial ou investigar uma invasão. Sigiloso: só o root
 * vê (SecurityConfiguration), e cada leitura fica na auditoria.
 */
@Service
public class AccessRecordService {

    private final JdbcTemplate jdbc;

    AccessRecordService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AccessRecordView(UUID id, UUID userId, String ip, String action, Instant createdAt) {
    }

    public PageResponse<AccessRecordView> list(UUID userId, Pageable pageable) {
        AuditContext.markSensitiveRead();
        Long total = jdbc.queryForObject("SELECT count(*) FROM access_records WHERE user_id = ?", Long.class, userId);
        List<AccessRecordView> content = jdbc.query("""
                SELECT id, user_id, ip, action, created_at FROM access_records
                WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> new AccessRecordView(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("ip"), rs.getString("action"), rs.getTimestamp("created_at").toInstant()),
                userId, pageable.getPageSize(), pageable.getOffset());
        long count = total == null ? 0 : total;
        return new PageResponse<>(content, pageable.getPageNumber(), pageable.getPageSize(), count,
                (int) Math.ceil((double) count / pageable.getPageSize()));
    }
}
