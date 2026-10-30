package br.com.readowl.readowl.support;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Usa o banco de READOWL_TEST_DB_URL quando definido; caso contrário, sobe um PostgreSQL com Testcontainers.
 * Em ambos os casos o schema é recriado do zero, o que também prova as migrations em banco vazio.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabaseConfiguration {

    static final String REQUIRED_SUFFIX = "_test";

    @Bean
    @ServiceConnection
    @ConditionalOnExpression("'${READOWL_TEST_DB_URL:}'.isEmpty()")
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18"))
                .withDatabaseName("readowl" + REQUIRED_SUFFIX);
    }

    @Bean
    FlywayMigrationStrategy cleanAndMigrateTestDatabase() {
        return flyway -> {
            requireTestDatabase(databaseName(flyway));
            flyway.clean();
            flyway.migrate();
        };
    }

    static void requireTestDatabase(String databaseName) {
        if (databaseName == null || !databaseName.endsWith(REQUIRED_SUFFIX)) {
            throw new IllegalStateException(
                    "Testes recusados: o schema seria apagado e o banco '" + databaseName
                            + "' não termina em " + REQUIRED_SUFFIX);
        }
    }

    private static String databaseName(Flyway flyway) {
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
            return connection.getCatalog();
        } catch (SQLException ex) {
            throw new IllegalStateException("Não foi possível identificar o banco de testes", ex);
        }
    }
}
