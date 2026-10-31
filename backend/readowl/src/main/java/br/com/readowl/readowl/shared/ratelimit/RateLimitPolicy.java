package br.com.readowl.readowl.shared.ratelimit;

import lombok.Getter;

import java.time.Duration;

@Getter
public enum RateLimitPolicy {

    LOGIN_IP(30, Duration.ofMinutes(15)),
    LOGIN_ACCOUNT(10, Duration.ofMinutes(15)),
    REGISTER_IP(10, Duration.ofHours(1)),
    REGISTER_EMAIL(5, Duration.ofHours(1)),
    ACCOUNT_MAIL_IP(10, Duration.ofHours(1)),
    ACCOUNT_MAIL_EMAIL(3, Duration.ofHours(1)),
    TOKEN_IP(20, Duration.ofMinutes(15)),
    PASSWORD_CONFIRMATION(10, Duration.ofMinutes(15));

    private final int limit;
    private final Duration window;

    RateLimitPolicy(int limit, Duration window) {
        this.limit = limit;
        this.window = window;
    }
}
