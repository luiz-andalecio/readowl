-- Extensões confiáveis: podem ser criadas pelo dono do banco, sem superusuário.
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- unaccent() não é imutável porque depende do search_path. Com tudo qualificado, pode ser usada em índices.
CREATE FUNCTION immutable_unaccent(text) RETURNS text AS $$
    SELECT public.unaccent('public.unaccent'::regdictionary, $1)
$$ LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT;

ALTER TABLE books ADD COLUMN last_chapter_published_at TIMESTAMPTZ;

ALTER TABLE books ADD COLUMN search_vector TSVECTOR GENERATED ALWAYS AS (
    setweight(to_tsvector('portuguese', immutable_unaccent(title)), 'A')
        || setweight(to_tsvector('portuguese', immutable_unaccent(synopsis)), 'B')
) STORED;

CREATE INDEX ix_books_search ON books USING gin (search_vector);
CREATE INDEX ix_books_title_trgm ON books USING gin (immutable_unaccent(lower(title)) gin_trgm_ops);
-- A busca compara a sigla em um OR: sem este índice, o OR inteiro vira varredura da tabela.
CREATE INDEX ix_books_acronym ON books (acronym);
CREATE INDEX ix_books_public_recent ON books (last_chapter_published_at DESC NULLS LAST)
    WHERE publication_state = 'PUBLISHED';

CREATE TABLE reading_progress (
    user_id    UUID        NOT NULL,
    book_id    UUID        NOT NULL,
    chapter_id UUID        NOT NULL,
    percent    INT         NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, book_id),
    CONSTRAINT fk_reading_progress_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_reading_progress_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE,
    CONSTRAINT fk_reading_progress_chapter_same_book FOREIGN KEY (chapter_id, book_id)
        REFERENCES chapters (id, book_id) ON DELETE CASCADE,
    CONSTRAINT ck_reading_progress_percent CHECK (percent BETWEEN 0 AND 100)
);

CREATE INDEX ix_reading_progress_book ON reading_progress (book_id);
CREATE INDEX ix_reading_progress_chapter ON reading_progress (chapter_id);
