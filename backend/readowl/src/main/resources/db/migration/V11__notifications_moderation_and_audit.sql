-- Conteúdo oculto pela moderação: fica fora do ar e o autor não pode republicá-lo até ser restaurado.
ALTER TABLE books ADD COLUMN moderated_at TIMESTAMPTZ;
ALTER TABLE chapters ADD COLUMN moderated_at TIMESTAMPTZ;
ALTER TABLE comments ADD COLUMN moderated_at TIMESTAMPTZ;

-- Moderar e restaurar também alteram updated_at, mas não são edição do texto.
ALTER TABLE comments ADD COLUMN edited_at TIMESTAMPTZ;
UPDATE comments SET edited_at = updated_at WHERE updated_at > created_at AND deleted_at IS NULL;

-- E-mails de novos capítulos só com consentimento: quem já tem conta não pediu para recebê-los.
ALTER TABLE users ADD COLUMN email_new_chapters BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    type            VARCHAR(50)  NOT NULL,
    book_id         UUID         NOT NULL,
    chapter_id      UUID         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    processed_at    TIMESTAMPTZ,
    failed_at       TIMESTAMPTZ,
    last_error      VARCHAR(200),
    CONSTRAINT fk_outbox_events_chapter_same_book FOREIGN KEY (chapter_id, book_id)
        REFERENCES chapters (id, book_id) ON DELETE CASCADE,
    CONSTRAINT ck_outbox_events_type CHECK (type IN ('CHAPTER_FIRST_PUBLISHED'))
);

CREATE INDEX ix_outbox_events_pending ON outbox_events (next_attempt_at)
    WHERE processed_at IS NULL AND failed_at IS NULL;
CREATE INDEX ix_outbox_events_processed ON outbox_events (processed_at) WHERE processed_at IS NOT NULL;
CREATE INDEX ix_outbox_events_book ON outbox_events (book_id);
CREATE INDEX ix_outbox_events_chapter ON outbox_events (chapter_id);

CREATE TABLE notifications (
    id                    UUID PRIMARY KEY,
    user_id               UUID         NOT NULL,
    type                  VARCHAR(50)  NOT NULL,
    book_id               UUID         NOT NULL,
    chapter_id            UUID         NOT NULL,
    event_id              UUID         NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    read_at               TIMESTAMPTZ,
    email_status          VARCHAR(20),
    email_attempts        INT          NOT NULL DEFAULT 0,
    email_next_attempt_at TIMESTAMPTZ,
    email_last_error      VARCHAR(200),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_notifications_chapter_same_book FOREIGN KEY (chapter_id, book_id)
        REFERENCES chapters (id, book_id) ON DELETE CASCADE,
    -- Reprocessar um evento não duplica a notificação.
    CONSTRAINT uq_notifications_user_event UNIQUE (user_id, event_id),
    CONSTRAINT ck_notifications_type CHECK (type IN ('NEW_CHAPTER')),
    CONSTRAINT ck_notifications_email_status CHECK (email_status IN ('PENDING', 'SENT', 'SKIPPED', 'FAILED'))
);

CREATE INDEX ix_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;
CREATE INDEX ix_notifications_email_pending ON notifications (email_next_attempt_at) WHERE email_status = 'PENDING';
CREATE INDEX ix_notifications_book ON notifications (book_id);
CREATE INDEX ix_notifications_chapter ON notifications (chapter_id);

-- O alvo não tem chave estrangeira: a denúncia e a cópia do conteúdo sobrevivem à exclusão dele.
CREATE TABLE reports (
    id              UUID PRIMARY KEY,
    version         BIGINT        NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    reporter_id     UUID          NOT NULL,
    target_type     VARCHAR(20)   NOT NULL,
    target_id       UUID          NOT NULL,
    target_owner_id UUID          NOT NULL,
    target_snapshot TEXT          NOT NULL,
    reason          VARCHAR(30)   NOT NULL,
    details         VARCHAR(1000),
    status          VARCHAR(20)   NOT NULL,
    resolution_note VARCHAR(1000),
    resolved_by_id  UUID,
    resolved_at     TIMESTAMPTZ,
    CONSTRAINT fk_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users (id),
    CONSTRAINT fk_reports_target_owner FOREIGN KEY (target_owner_id) REFERENCES users (id),
    CONSTRAINT fk_reports_resolved_by FOREIGN KEY (resolved_by_id) REFERENCES users (id),
    CONSTRAINT ck_reports_target_type CHECK (target_type IN ('BOOK', 'CHAPTER', 'COMMENT', 'USER')),
    CONSTRAINT ck_reports_reason CHECK (reason IN ('SPAM', 'ABUSE', 'ILLEGAL_CONTENT', 'COPYRIGHT', 'OTHER')),
    CONSTRAINT ck_reports_status CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED'))
);

-- A mesma pessoa não abre duas denúncias sobre o mesmo alvo, mas pode denunciar de novo depois de encerrada.
CREATE UNIQUE INDEX uq_reports_open_by_reporter ON reports (reporter_id, target_type, target_id) WHERE status = 'OPEN';
CREATE INDEX ix_reports_status_created ON reports (status, created_at);
CREATE INDEX ix_reports_target ON reports (target_type, target_id);
CREATE INDEX ix_reports_target_owner ON reports (target_owner_id);
CREATE INDEX ix_reports_resolved_by ON reports (resolved_by_id) WHERE resolved_by_id IS NOT NULL;

CREATE TABLE audit_log (
    id             UUID PRIMARY KEY,
    actor_id       UUID         NOT NULL,
    action         VARCHAR(200) NOT NULL,
    resource       VARCHAR(500) NOT NULL,
    outcome        VARCHAR(20)  NOT NULL,
    correlation_id VARCHAR(36),
    details        JSONB,
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_audit_log_actor FOREIGN KEY (actor_id) REFERENCES users (id),
    CONSTRAINT ck_audit_log_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED'))
);

CREATE INDEX ix_audit_log_created ON audit_log (created_at DESC);
CREATE INDEX ix_audit_log_actor_created ON audit_log (actor_id, created_at DESC);
CREATE INDEX ix_audit_log_resource_created ON audit_log (resource, created_at DESC);
