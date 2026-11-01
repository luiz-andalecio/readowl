package br.com.readowl.readowl.shared.ratelimit;

import lombok.Getter;

import java.time.Duration;

/**
 * Nos limites por e-mail, o estrito vale para o par conta e IP, e o amplo, para a conta inteira. Assim, quem
 * conhece o e-mail de alguém não consegue, de um único IP, impedir essa pessoa de entrar ou de recuperar a senha.
 */
@Getter
public enum RateLimitPolicy {

    LOGIN_IP(30, Duration.ofMinutes(15)),
    LOGIN_ACCOUNT_IP(10, Duration.ofMinutes(15)),
    LOGIN_ACCOUNT(100, Duration.ofMinutes(15)),
    REGISTER_IP(10, Duration.ofHours(1)),
    REGISTER_EMAIL(5, Duration.ofHours(1)),
    ACCOUNT_MAIL_IP(10, Duration.ofHours(1)),
    ACCOUNT_MAIL_EMAIL_IP(3, Duration.ofHours(1)),
    ACCOUNT_MAIL_EMAIL(10, Duration.ofHours(1)),
    TOKEN_IP(20, Duration.ofMinutes(15)),
    PASSWORD_CONFIRMATION(10, Duration.ofMinutes(15)),
    COMMENT_ACCOUNT(10, Duration.ofMinutes(1)),
    COMMENT_IP(60, Duration.ofMinutes(1)),
    INTERACTION_ACCOUNT(60, Duration.ofMinutes(1)),
    INTERACTION_IP(300, Duration.ofMinutes(1)),
    VIEW_ACCOUNT(120, Duration.ofMinutes(1)),
    VIEW_IP(600, Duration.ofMinutes(1)),
    REPORT_ACCOUNT(10, Duration.ofHours(1)),
    REPORT_IP(30, Duration.ofHours(1)),
    BOOK_CREATION_ACCOUNT(10, Duration.ofDays(1)),
    TAG_WRITE_ACCOUNT(30, Duration.ofHours(1)),
    AUTHORING_ACCOUNT(120, Duration.ofMinutes(1)),
    DRAFT_SAVE_ACCOUNT(120, Duration.ofMinutes(1)),
    PUBLICATION_ACCOUNT(120, Duration.ofHours(1)),
    AUDIT_DENIED_ACCOUNT(60, Duration.ofHours(1));

    private final int limit;
    private final Duration window;

    RateLimitPolicy(int limit, Duration window) {
        this.limit = limit;
        this.window = window;
    }
}
