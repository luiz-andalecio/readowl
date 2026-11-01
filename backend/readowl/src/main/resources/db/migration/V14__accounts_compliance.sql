-- A linha da conta excluída continua, sem dados pessoais: auditoria, denúncias e comentários apontam para ela.
ALTER TABLE users DROP CONSTRAINT ck_users_status;
ALTER TABLE users ADD CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'));
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;
-- Contas anteriores, e as criadas pelo Google, aceitam os termos no primeiro uso.
ALTER TABLE users ADD COLUMN terms_version VARCHAR(20);
ALTER TABLE users ADD COLUMN terms_accepted_at TIMESTAMPTZ;
CREATE INDEX ix_users_unverified ON users (created_at) WHERE email_verified_at IS NULL AND status = 'ACTIVE';

ALTER TABLE books ADD COLUMN content_rating VARCHAR(10) NOT NULL DEFAULT 'GENERAL';
ALTER TABLE books ADD CONSTRAINT ck_books_content_rating CHECK (content_rating IN ('GENERAL', 'ADULT'));

-- Apagar o texto de um comentário excluído impede restaurá-lo depois.
ALTER TABLE comments ADD COLUMN content_purged_at TIMESTAMPTZ;
CREATE INDEX ix_comments_deleted_with_content ON comments (deleted_at)
    WHERE deleted_at IS NOT NULL AND content_purged_at IS NULL;

CREATE INDEX ix_notifications_created ON notifications (created_at);
CREATE INDEX ix_reports_resolved ON reports (resolved_at) WHERE status <> 'OPEN';

ALTER TABLE account_mail_queue DROP CONSTRAINT ck_account_mail_queue_type;
ALTER TABLE account_mail_queue ADD CONSTRAINT ck_account_mail_queue_type CHECK (type IN (
    'VERIFY_EMAIL', 'RESET_PASSWORD', 'ACCOUNT_ALREADY_EXISTS', 'PASSWORD_CHANGED', 'GOOGLE_LINKED',
    'EMAIL_CHANGE_CONFIRMATION', 'EMAIL_CHANGED', 'ACCOUNT_DELETED'));
-- O endereço novo de uma troca de e-mail ainda não identifica a conta.
ALTER TABLE account_mail_queue ADD COLUMN user_id UUID;
ALTER TABLE account_mail_queue ADD CONSTRAINT fk_account_mail_queue_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
CREATE INDEX ix_account_mail_queue_user ON account_mail_queue (user_id) WHERE user_id IS NOT NULL;

CREATE TABLE email_change_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL,
    token_hash  VARCHAR(255) NOT NULL,
    new_email   VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT fk_email_change_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_email_change_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_email_change_tokens_email_normalized CHECK (new_email = lower(new_email))
);

CREATE INDEX ix_email_change_tokens_user ON email_change_tokens (user_id);
CREATE INDEX ix_email_change_tokens_expires ON email_change_tokens (expires_at);

-- Registro de acesso: quem fez o quê, de qual IP e quando. Sem chave estrangeira: precisa sobreviver à exclusão da conta.
CREATE TABLE access_records (
    id         UUID PRIMARY KEY,
    user_id    UUID         NOT NULL,
    ip         VARCHAR(45),
    action     VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_access_records_user_created ON access_records (user_id, created_at DESC);
CREATE INDEX ix_access_records_created ON access_records (created_at);

-- O usuário da aplicação não apaga auditoria nem registros de acesso (ver afterMigrate.sql). Estas funções rodam com o
-- dono do schema e só removem linhas antigas: quem compromete a aplicação não consegue apagar o que é recente.
CREATE FUNCTION purge_audit_log(retention INTERVAL) RETURNS BIGINT
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    removed BIGINT;
BEGIN
    IF retention < INTERVAL '365 days' THEN
        RAISE EXCEPTION 'A auditoria fica pelo menos 365 dias';
    END IF;
    DELETE FROM audit_log WHERE created_at < now() - retention;
    GET DIAGNOSTICS removed = ROW_COUNT;
    RETURN removed;
END
$$;

CREATE FUNCTION purge_access_records(retention INTERVAL) RETURNS BIGINT
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    removed BIGINT;
BEGIN
    IF retention < INTERVAL '180 days' THEN
        RAISE EXCEPTION 'Os registros de acesso ficam pelo menos 180 dias';
    END IF;
    DELETE FROM access_records WHERE created_at < now() - retention;
    GET DIAGNOSTICS removed = ROW_COUNT;
    RETURN removed;
END
$$;
