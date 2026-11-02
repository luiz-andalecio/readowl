-- Endereços antigos, para links compartilhados e buscadores acharem a obra depois de renomeada. O endereço atual de outra
-- obra sempre vence: quem passa a usar um slug remove a linha dele daqui.
CREATE TABLE book_slug_history (
    old_slug   VARCHAR(220) PRIMARY KEY,
    book_id    UUID         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_book_slug_history_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE
);

CREATE INDEX ix_book_slug_history_book ON book_slug_history (book_id);

CREATE TABLE chapter_slug_history (
    book_id    UUID         NOT NULL,
    old_slug   VARCHAR(220) NOT NULL,
    chapter_id UUID         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (book_id, old_slug),
    CONSTRAINT fk_chapter_slug_history_chapter FOREIGN KEY (chapter_id, book_id)
        REFERENCES chapters (id, book_id) ON DELETE CASCADE
);

CREATE INDEX ix_chapter_slug_history_chapter ON chapter_slug_history (chapter_id);

-- Avisos de moderação usam a mesma caixa de notificações, mas podem não ter obra nem capítulo, nem vêm de um evento.
ALTER TABLE notifications ALTER COLUMN book_id DROP NOT NULL;
ALTER TABLE notifications ALTER COLUMN chapter_id DROP NOT NULL;
ALTER TABLE notifications ALTER COLUMN event_id DROP NOT NULL;
ALTER TABLE notifications ADD COLUMN message VARCHAR(1000);
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'NEW_CHAPTER', 'CONTENT_HIDDEN', 'CONTENT_RESTORED', 'REPORT_RESOLVED', 'REPORT_DISMISSED'));
