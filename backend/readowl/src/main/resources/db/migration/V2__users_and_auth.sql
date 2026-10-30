CREATE TABLE users (
    id                UUID PRIMARY KEY,
    version           BIGINT       NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    name              VARCHAR(255) NOT NULL,
    username          VARCHAR(30)  NOT NULL,
    email             VARCHAR(255) NOT NULL,
    password_hash     VARCHAR(255),
    description       VARCHAR(2000),
    role              VARCHAR(20)  NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    email_verified_at TIMESTAMPTZ,
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'MODERATOR', 'ADMIN')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_users_email_normalized CHECK (email = lower(email)),
    CONSTRAINT ck_users_username_format CHECK (username ~ '^[a-z0-9][a-z0-9_-]{2,29}$')
);

CREATE UNIQUE INDEX ux_users_email_lower ON users (lower(email));
CREATE UNIQUE INDEX ux_users_username ON users (username);

CREATE TABLE accounts (
    id                  UUID PRIMARY KEY,
    user_id             UUID         NOT NULL,
    provider            VARCHAR(100) NOT NULL,
    provider_account_id VARCHAR(255) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_accounts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_accounts_provider_identity UNIQUE (provider, provider_account_id),
    CONSTRAINT uq_accounts_user_provider UNIQUE (user_id, provider)
);

CREATE TABLE verification_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL,
    token_hash  VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT fk_verification_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_verification_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX ix_verification_tokens_user ON verification_tokens (user_id);

CREATE TABLE password_reset_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL,
    token_hash  VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT fk_password_reset_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_password_reset_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX ix_password_reset_tokens_user ON password_reset_tokens (user_id);
