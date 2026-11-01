package br.com.readowl.readowl.shared.persistence;

import br.com.readowl.readowl.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migrations não rodam só em banco vazio: leva um schema à parte até a versão em que o banco de desenvolvimento
 * parou, com dados, e o atualiza até a versão atual. O schema principal, usado pelos demais testes, não é tocado.
 */
@IntegrationTest
class MigrationUpgradeIntegrationTest {

    private static final String SCHEMA = "upgrade_test";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID author;
    private UUID book;
    private UUID chapter;

    @BeforeEach
    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private void seedUser() {
        author = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO upgrade_test.users (id, version, created_at, updated_at, name, username, email, role, status,
                                                email_verified_at)
                VALUES (?, 0, now(), now(), 'Autora', 'autora', 'autora@example.com', 'USER', 'ACTIVE', now())
                """, author);
    }

    private void seedBookWithoutAcronym() {
        jdbc.update("""
                INSERT INTO upgrade_test.books (id, version, created_at, updated_at, title, slug, synopsis, status,
                                                publication_state, author_id)
                VALUES (gen_random_uuid(), 0, now(), now(), 'Obra', 'obra', 'Sinopse', 'ONGOING', 'DRAFT', ?)
                """, author);
    }

    private void seedContent() {
        book = UUID.randomUUID();
        chapter = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO upgrade_test.books (id, version, created_at, updated_at, title, acronym, slug, synopsis, status,
                                                publication_state, author_id)
                VALUES (?, 0, now(), now(), 'Obra Antiga', 'OA', 'oa-obra-antiga', 'Uma sinopse com ação.', 'ONGOING',
                        'PUBLISHED', ?)
                """, book, author);
        jdbc.update("""
                INSERT INTO upgrade_test.chapters (id, version, created_at, updated_at, title, slug, state, display_order, book_id)
                VALUES (?, 0, now(), now(), 'Capítulo', 'capitulo', 'DRAFT', 1, ?)
                """, chapter, book);
        jdbc.update("""
                INSERT INTO upgrade_test.comments (id, version, created_at, updated_at, content, user_id, book_id)
                VALUES (gen_random_uuid(), 0, now() - interval '1 day', now(), 'Editado', ?, ?)
                """, author, book);
    }

    /**
     * Até a versão 6 nenhuma rota criava obras, então é o que um banco real pode ter ali. A versão 8 exige a
     * sigla das obras.
     */
    @Test
    void upgradesADatabaseWithDataFromVersion6ToTheCurrentOne() {
        assertThat(flyway("6").migrate().success).isTrue();
        seedUser();
        assertThat(flyway("8").migrate().success).isTrue();
        seedContent();

        assertThat(flyway("latest").migrate().success).isTrue();

        assertThat(flyway("latest").info().pending()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT username FROM upgrade_test.users WHERE id = ?", String.class, author)).isEqualTo("autora");
        assertThat(jdbc.queryForObject("""
                SELECT search_vector @@ to_tsquery('portuguese', 'antiga') FROM upgrade_test.books WHERE id = ?
                """, Boolean.class, book)).isTrue();
        assertThat(jdbc.queryForObject("SELECT email_new_chapters FROM upgrade_test.users WHERE id = ?", Boolean.class, author)).isFalse();
        assertThat(jdbc.queryForObject("SELECT edited_at IS NOT NULL FROM upgrade_test.comments WHERE book_id = ?", Boolean.class, book)).isTrue();
        assertThat(jdbc.queryForObject("SELECT state FROM upgrade_test.chapters WHERE id = ?", String.class, chapter)).isEqualTo("DRAFT");
    }

    @Test
    void versionThatRequiresTheAcronymFailsWithoutChangingAnythingIfThereAreBooks() {
        flyway("6").migrate();
        seedUser();
        seedBookWithoutAcronym();

        assertThatThrownBy(() -> flyway("8").migrate()).isInstanceOf(FlywayException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM upgrade_test.books", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'upgrade_test' AND table_name = 'books' AND column_name = 'acronym'
                """, Integer.class)).isZero();
    }

    @Test
    void migrationsRunWithoutTheTimeLimitOfNormalQueries() {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .locations("classpath:db/timeoutprobe", "classpath:db/migration")
                .target("0.1")
                .load()
                .migrate();

        assertThat(jdbc.queryForObject("SELECT value FROM upgrade_test.timeout_probe", String.class)).isEqualTo("0");
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).as("sem afetar as conexões do pool").isEqualTo("30s");
    }

    @Test
    void repeatingTheMigrationDoesNothing() {
        flyway("latest").migrate();

        assertThat(flyway("latest").migrate().migrationsExecuted).isZero();
    }
}
