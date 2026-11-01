package br.com.readowl.readowl.auth.infrastructure;

import br.com.readowl.readowl.shared.config.ReadowlProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
class PasswordHashingConfiguration {

    private static final String ENCODER_ID = "argon2id";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int PARALLELISM = 1;
    // Mínimo recomendado pela OWASP para Argon2id: 19 MiB de memória e 2 iterações.
    private static final int MEMORY_KIB = 19_456;
    private static final int ITERATIONS = 2;
    private static final Duration MAX_WAIT = Duration.ofSeconds(5);

    /**
     * O prefixo {argon2id} em cada hash permite trocar de algoritmo sem invalidar as senhas existentes.
     */
    @Bean
    PasswordEncoder passwordEncoder(ReadowlProperties properties) {
        return new ConcurrencyLimitedPasswordEncoder(new DelegatingPasswordEncoder(ENCODER_ID, Map.of(ENCODER_ID,
                new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS))),
                properties.maxConcurrentPasswordHashes(), MAX_WAIT);
    }
}
