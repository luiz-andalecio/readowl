package br.com.readowl.readowl.auth.infrastructure;

import br.com.readowl.readowl.shared.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConcurrencyLimitedPasswordEncoderTest {

    private final CountDownLatch hashing = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private final PasswordEncoder blocking = new PasswordEncoder() {

        @Override
        public String encode(CharSequence rawPassword) {
            hashing.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return "hash";
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return "certa".contentEquals(rawPassword);
        }
    };

    @Test
    void refusesWhenTheLimitIsTakenAndWorksAgainAfterwards() throws Exception {
        PasswordEncoder limited = new ConcurrencyLimitedPasswordEncoder(blocking, 1, Duration.ofMillis(50));
        CompletableFuture<String> running = CompletableFuture.supplyAsync(() -> limited.encode("senha"));
        assertThat(hashing.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> limited.matches("certa", "hash"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getCode()).isEqualTo("AUTHENTICATION_BUSY");
                });

        release.countDown();
        assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo("hash");
        assertThat(limited.matches("certa", "hash")).isTrue();
    }

    @Test
    void aFailureDuringHashingReleasesThePermit() {
        PasswordEncoder failing = new PasswordEncoder() {

            @Override
            public String encode(CharSequence rawPassword) {
                throw new IllegalStateException("falha");
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return true;
            }
        };
        PasswordEncoder limited = new ConcurrencyLimitedPasswordEncoder(failing, 1, Duration.ofMillis(50));

        assertThatThrownBy(() -> limited.encode("senha")).isInstanceOf(IllegalStateException.class);

        assertThat(limited.matches("senha", "hash")).isTrue();
    }
}
