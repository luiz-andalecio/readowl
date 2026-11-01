-- E-mails de conta ainda não entregues. O token não fica aqui: é gerado na hora do envio, e a linha some depois dele.
CREATE TABLE account_mail_queue (
    id              UUID PRIMARY KEY,
    type            VARCHAR(30)  NOT NULL,
    email           VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    failed_at       TIMESTAMPTZ,
    last_error      VARCHAR(200),
    CONSTRAINT ck_account_mail_queue_type CHECK (type IN ('VERIFY_EMAIL', 'RESET_PASSWORD', 'ACCOUNT_ALREADY_EXISTS'))
);

CREATE INDEX ix_account_mail_queue_pending ON account_mail_queue (next_attempt_at) WHERE failed_at IS NULL;
CREATE INDEX ix_account_mail_queue_failed ON account_mail_queue (failed_at) WHERE failed_at IS NOT NULL;

-- Navegadores em que a conta já entrou com a senha. Só o hash do token fica guardado, sem IP nem dados do aparelho.
CREATE TABLE known_devices (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id    UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_known_devices_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX ix_known_devices_user_created ON known_devices (user_id, created_at DESC);
CREATE INDEX ix_known_devices_expires ON known_devices (expires_at);
