package br.com.readowl.readowl.shared.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMigrationTest {

    private static Map<String, String> environment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("DB_HOST", "banco");
        environment.put("DB_PORT", "5432");
        environment.put("DB_NAME", "readowl");
        environment.put("DB_SSL_MODE", "verify-full");
        environment.put("DB_SSL_ROOT_CERT", "/certs/ca.crt");
        environment.put("DB_USER", "readowl_app");
        environment.put("DB_MIGRATION_USER", "readowl_migrator");
        environment.put("DB_MIGRATION_PASSWORD", "segredo");
        return environment;
    }

    @Test
    void theAddressCarriesTheTlsOptionsAndNoPassword() {
        String url = DatabaseMigration.url(environment()::get);

        assertThat(url).startsWith("jdbc:postgresql://banco:5432/readowl?")
                .contains("sslmode=verify-full").contains("sslrootcert=/certs/ca.crt")
                .doesNotContain("segredo");
    }

    @Test
    void theMigrationConnectsAsTheSchemaOwnerAndKnowsTheApplicationUser() {
        var configuration = DatabaseMigration.flyway(environment()::get).getConfiguration();

        assertThat(configuration.getUser()).isEqualTo("readowl_migrator");
        assertThat(configuration.getPlaceholders()).containsEntry("appUser", "readowl_app");
    }

    @Test
    void aMissingVariableIsNamedWithoutShowingTheOthers() {
        Map<String, String> environment = environment();
        environment.remove("DB_SSL_MODE");

        assertThatThrownBy(() -> DatabaseMigration.flyway(environment::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_SSL_MODE")
                .hasMessageNotContaining("segredo");
    }
}
