package br.com.readowl.readowl.auth.infrastructure;

import br.com.readowl.readowl.shared.exception.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Limita os hashes simultâneos. Cada um reserva memória e processador, e um ataque ao login com muitos pedidos
 * ao mesmo tempo poderia esgotar a memória da aplicação. Quem passa da espera recebe 503.
 */
final class ConcurrencyLimitedPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final Semaphore permits;
    private final Duration maxWait;

    ConcurrencyLimitedPasswordEncoder(PasswordEncoder delegate, int maxConcurrent, Duration maxWait) {
        this.delegate = delegate;
        this.permits = new Semaphore(maxConcurrent);
        this.maxWait = maxWait;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return limited(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return limited(() -> delegate.matches(rawPassword, encodedPassword));
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    private <T> T limited(Supplier<T> hashing) {
        acquire();
        try {
            return hashing.get();
        } finally {
            permits.release();
        }
    }

    private void acquire() {
        try {
            if (permits.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        throw ApiException.serviceUnavailable("AUTHENTICATION_BUSY", "Muitas tentativas de acesso no momento. Tente novamente.");
    }
}
