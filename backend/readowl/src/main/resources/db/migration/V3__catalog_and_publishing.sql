CREATE TABLE genres (
    id         UUID PRIMARY KEY,
    version    BIGINT       NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    name       VARCHAR(100) NOT NULL
);

CREATE UNIQUE INDEX ux_genres_name_lower ON genres (lower(name));

CREATE TABLE tags (
    id         UUID PRIMARY KEY,
    version    BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    name       VARCHAR(50) NOT NULL
);

CREATE UNIQUE INDEX ux_tags_name_lower ON tags (lower(name));

CREATE TABLE books (
    id                UUID PRIMARY KEY,
    version           BIGINT        NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    title             VARCHAR(200)  NOT NULL,
    slug              VARCHAR(220)  NOT NULL,
    synopsis          VARCHAR(2000) NOT NULL,
    release_frequency VARCHAR(50),
    status            VARCHAR(20)   NOT NULL,
    publication_state VARCHAR(20)   NOT NULL,
    view_count        BIGINT        NOT NULL DEFAULT 0,
    author_id         UUID          NOT NULL,
    CONSTRAINT fk_books_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT uq_books_slug UNIQUE (slug),
    CONSTRAINT ck_books_status CHECK (status IN ('ONGOING', 'COMPLETED', 'HIATUS')),
    CONSTRAINT ck_books_publication_state CHECK (publication_state IN ('DRAFT', 'PUBLISHED', 'UNPUBLISHED')),
    CONSTRAINT ck_books_view_count CHECK (view_count >= 0)
);

CREATE INDEX ix_books_author ON books (author_id);

CREATE TABLE book_genres (
    book_id  UUID NOT NULL,
    genre_id UUID NOT NULL,
    PRIMARY KEY (book_id, genre_id),
    CONSTRAINT fk_book_genres_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE,
    CONSTRAINT fk_book_genres_genre FOREIGN KEY (genre_id) REFERENCES genres (id) ON DELETE RESTRICT
);

CREATE INDEX ix_book_genres_genre ON book_genres (genre_id);

CREATE TABLE book_tags (
    book_id UUID NOT NULL,
    tag_id  UUID NOT NULL,
    PRIMARY KEY (book_id, tag_id),
    CONSTRAINT fk_book_tags_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE,
    CONSTRAINT fk_book_tags_tag FOREIGN KEY (tag_id) REFERENCES tags (id) ON DELETE RESTRICT
);

CREATE INDEX ix_book_tags_tag ON book_tags (tag_id);

CREATE TABLE volumes (
    id            UUID PRIMARY KEY,
    version       BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    title         VARCHAR(200) NOT NULL,
    display_order INT          NOT NULL,
    book_id       UUID         NOT NULL,
    CONSTRAINT fk_volumes_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE,
    CONSTRAINT uq_volumes_id_book UNIQUE (id, book_id),
    CONSTRAINT uq_volumes_book_order UNIQUE (book_id, display_order) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE chapters (
    id                    UUID PRIMARY KEY,
    version               BIGINT       NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    title                 VARCHAR(200) NOT NULL,
    slug                  VARCHAR(220) NOT NULL,
    state                 VARCHAR(20)  NOT NULL,
    display_order         INT          NOT NULL,
    view_count            BIGINT       NOT NULL DEFAULT 0,
    book_id               UUID         NOT NULL,
    volume_id             UUID,
    published_revision_id UUID,
    CONSTRAINT fk_chapters_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE,
    -- NO ACTION (e não RESTRICT): a exclusão em cascata de uma obra remove volumes e capítulos no mesmo comando.
    CONSTRAINT fk_chapters_volume_same_book FOREIGN KEY (volume_id, book_id) REFERENCES volumes (id, book_id),
    CONSTRAINT uq_chapters_id_book UNIQUE (id, book_id),
    CONSTRAINT uq_chapters_book_slug UNIQUE (book_id, slug),
    CONSTRAINT uq_chapters_book_volume_order UNIQUE NULLS NOT DISTINCT (book_id, volume_id, display_order)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_chapters_state CHECK (state IN ('DRAFT', 'PUBLISHED', 'UNPUBLISHED')),
    CONSTRAINT ck_chapters_published_has_revision CHECK (state <> 'PUBLISHED' OR published_revision_id IS NOT NULL),
    CONSTRAINT ck_chapters_view_count CHECK (view_count >= 0)
);

CREATE INDEX ix_chapters_volume ON chapters (volume_id) WHERE volume_id IS NOT NULL;

CREATE TABLE chapter_drafts (
    chapter_id     UUID PRIMARY KEY,
    version        BIGINT      NOT NULL,
    document       JSONB       NOT NULL,
    format_version INT         NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_chapter_drafts_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id) ON DELETE CASCADE,
    CONSTRAINT ck_chapter_drafts_format_version CHECK (format_version > 0)
);

CREATE TABLE chapter_revisions (
    id              UUID PRIMARY KEY,
    chapter_id      UUID        NOT NULL,
    revision_number INT         NOT NULL,
    document        JSONB       NOT NULL,
    format_version  INT         NOT NULL,
    plain_text      TEXT        NOT NULL,
    html            TEXT        NOT NULL,
    created_by      UUID        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_chapter_revisions_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id) ON DELETE CASCADE,
    CONSTRAINT fk_chapter_revisions_author FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uq_chapter_revisions_id_chapter UNIQUE (id, chapter_id),
    CONSTRAINT uq_chapter_revisions_number UNIQUE (chapter_id, revision_number),
    CONSTRAINT ck_chapter_revisions_number CHECK (revision_number > 0),
    CONSTRAINT ck_chapter_revisions_format_version CHECK (format_version > 0)
);

CREATE INDEX ix_chapter_revisions_created_by ON chapter_revisions (created_by);

-- A revisão publicada precisa pertencer ao próprio capítulo.
ALTER TABLE chapters
    ADD CONSTRAINT fk_chapters_published_revision_same_chapter
        FOREIGN KEY (published_revision_id, id) REFERENCES chapter_revisions (id, chapter_id);

CREATE INDEX ix_chapters_published_revision ON chapters (published_revision_id) WHERE published_revision_id IS NOT NULL;
