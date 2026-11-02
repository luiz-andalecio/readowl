package br.com.readowl.readowl.moderation.infrastructure;

import br.com.readowl.readowl.media.application.ImageHolds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A cópia guardada na denúncia cita o endereço da imagem, que contém a chave do arquivo. Enquanto a denúncia estiver aberta,
 * o arquivo é prova, mesmo que a pessoa troque a imagem ou exclua a obra ou a conta.
 */
@Component
class OpenReportImageHolds implements ImageHolds {

    private final JdbcTemplate jdbc;

    OpenReportImageHolds(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isHeld(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM reports WHERE status = 'OPEN' AND position(? in target_snapshot) > 0)", Boolean.class, key));
    }
}
