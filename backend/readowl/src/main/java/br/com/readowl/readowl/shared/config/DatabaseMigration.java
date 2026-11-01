package br.com.readowl.readowl.shared.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.flywaydb.core.Flyway;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Aplica as migrations com o usuário dono do schema e termina, sem subir a aplicação. Roda antes de cada implantação,
 * para que a aplicação em execução não tenha uma credencial capaz de alterar o schema ou apagar a auditoria.
 */
public final class DatabaseMigration {

    private DatabaseMigration() {
    }

    public static void main(String[] args) {
        // Sem o Spring, o padrão do Logback é DEBUG, o que imprimiria o texto de cada migration.
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
        flyway(System::getenv).migrate();
    }

    static Flyway flyway(UnaryOperator<String> environment) {
        return Flyway.configure()
                .dataSource(url(environment), required(environment, "DB_MIGRATION_USER"),
                        required(environment, "DB_MIGRATION_PASSWORD"))
                .placeholders(Map.of("appUser", required(environment, "DB_USER")))
                .load();
    }

    static String url(UnaryOperator<String> environment) {
        String rootCertificate = environment.apply("DB_SSL_ROOT_CERT");
        return "jdbc:postgresql://" + required(environment, "DB_HOST") + ":" + required(environment, "DB_PORT") + "/"
                + required(environment, "DB_NAME") + "?sslmode=" + required(environment, "DB_SSL_MODE")
                + "&sslrootcert=" + (rootCertificate == null ? "" : rootCertificate)
                // Sem o detalhe do erro do PostgreSQL, que traz os valores das linhas para os logs.
                + "&logServerErrorDetail=false";
    }

    private static String required(UnaryOperator<String> environment, String name) {
        String value = environment.apply(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Variável de ambiente obrigatória ausente: " + name);
        }
        return value;
    }
}
