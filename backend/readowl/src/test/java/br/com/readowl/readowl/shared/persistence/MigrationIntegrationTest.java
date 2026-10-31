package br.com.readowl.readowl.shared.persistence;

import br.com.readowl.readowl.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class MigrationIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void appliesEveryMigrationOnAnEmptyDatabase() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).isNotEmpty();
        assertThat(Arrays.stream(applied).map(MigrationInfo::getState)).containsOnly(MigrationState.SUCCESS);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void createsSpringSessionAndDomainTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

        assertThat(tables).contains(
                "spring_session", "spring_session_attributes",
                "users", "accounts", "verification_tokens", "password_reset_tokens",
                "genres", "tags", "books", "book_genres", "book_tags",
                "volumes", "chapters", "chapter_drafts", "chapter_revisions",
                "book_follows", "book_ratings", "comments", "comment_likes", "chapter_views",
                "banners", "rate_limit_buckets", "reading_progress", "chapter_reactions");
    }

    @Test
    void storesEveryTimestampWithTimeZone() {
        List<String> columns = jdbc.queryForList("""
                SELECT table_name || '.' || column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND data_type = 'timestamp without time zone'
                  AND table_name <> 'flyway_schema_history'
                """, String.class);

        assertThat(columns).isEmpty();
    }

    @Test
    void usesUtf8Database() {
        assertThat(jdbc.queryForObject("SHOW server_encoding", String.class)).isEqualTo("UTF8");
    }

    @Test
    void seedsInitialGenresKeepingAccents() {
        List<String> genres = jdbc.queryForList("SELECT name FROM genres", String.class);

        assertThat(genres).hasSize(55).contains("Ação", "Não-Humano", "Pós-Apocalíptico", "Slice of Life");
    }
}
