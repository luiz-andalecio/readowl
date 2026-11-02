package br.com.readowl.readowl.user.infrastructure;

import br.com.readowl.readowl.user.domain.ExternalLinkType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class UserLinks {

    private final JdbcTemplate jdbc;

    UserLinks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ExternalLink(ExternalLinkType type, String url) {
    }

    public List<ExternalLink> of(UUID userId) {
        return jdbc.query("SELECT type, url FROM user_links WHERE user_id = ? ORDER BY position",
                (rs, row) -> new ExternalLink(ExternalLinkType.valueOf(rs.getString("type")), rs.getString("url")), userId);
    }

    /**
     * Troca a lista inteira, na ordem recebida.
     */
    public void replace(UUID userId, List<ExternalLink> links) {
        jdbc.update("DELETE FROM user_links WHERE user_id = ?", userId);
        for (int position = 0; position < links.size(); position++) {
            ExternalLink link = links.get(position);
            jdbc.update("INSERT INTO user_links (user_id, position, type, url) VALUES (?, ?, ?, ?)",
                    userId, position, link.type().name(), link.url());
        }
    }
}
