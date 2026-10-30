CREATE TABLE book_follows (
    user_id    UUID        NOT NULL,
    book_id    UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, book_id),
    CONSTRAINT fk_book_follows_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_book_follows_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE
);

CREATE INDEX ix_book_follows_book ON book_follows (book_id);

CREATE TABLE book_ratings (
    user_id    UUID        NOT NULL,
    book_id    UUID        NOT NULL,
    score      INT         NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, book_id),
    CONSTRAINT fk_book_ratings_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_book_ratings_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE,
    CONSTRAINT ck_book_ratings_score CHECK (score BETWEEN 1 AND 5)
);

CREATE INDEX ix_book_ratings_book ON book_ratings (book_id);

CREATE TABLE comments (
    id         UUID PRIMARY KEY,
    version    BIGINT        NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL,
    updated_at TIMESTAMPTZ   NOT NULL,
    content    VARCHAR(5000) NOT NULL,
    user_id    UUID          NOT NULL,
    book_id    UUID          NOT NULL,
    chapter_id UUID,
    parent_id  UUID,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_comments_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_comments_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE,
    CONSTRAINT fk_comments_chapter_same_book FOREIGN KEY (chapter_id, book_id)
        REFERENCES chapters (id, book_id) ON DELETE CASCADE,
    CONSTRAINT uq_comments_id_book UNIQUE (id, book_id),
    CONSTRAINT fk_comments_parent_same_book FOREIGN KEY (parent_id, book_id)
        REFERENCES comments (id, book_id) ON DELETE CASCADE,
    CONSTRAINT ck_comments_not_own_parent CHECK (parent_id <> id)
);

CREATE INDEX ix_comments_book_created ON comments (book_id, created_at);
CREATE INDEX ix_comments_chapter_created ON comments (chapter_id, created_at) WHERE chapter_id IS NOT NULL;
CREATE INDEX ix_comments_parent ON comments (parent_id) WHERE parent_id IS NOT NULL;
CREATE INDEX ix_comments_user ON comments (user_id);

CREATE TABLE comment_likes (
    user_id    UUID        NOT NULL,
    comment_id UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, comment_id),
    CONSTRAINT fk_comment_likes_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_comment_likes_comment FOREIGN KEY (comment_id) REFERENCES comments (id) ON DELETE CASCADE
);

CREATE INDEX ix_comment_likes_comment ON comment_likes (comment_id);

CREATE TABLE chapter_views (
    user_id         UUID        NOT NULL,
    chapter_id      UUID        NOT NULL,
    last_counted_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, chapter_id),
    CONSTRAINT fk_chapter_views_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_chapter_views_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id) ON DELETE CASCADE
);

CREATE INDEX ix_chapter_views_chapter ON chapter_views (chapter_id);
