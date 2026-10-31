-- Um usuário tem uma reação por capítulo: reagir de novo troca a anterior.
CREATE TABLE chapter_reactions (
    user_id    UUID        NOT NULL,
    chapter_id UUID        NOT NULL,
    type       VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, chapter_id),
    CONSTRAINT fk_chapter_reactions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_chapter_reactions_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id) ON DELETE CASCADE,
    CONSTRAINT ck_chapter_reactions_type CHECK (type IN ('LIKE', 'LOVE', 'LAUGH', 'WOW', 'SAD', 'ANGRY'))
);

CREATE INDEX ix_chapter_reactions_chapter ON chapter_reactions (chapter_id);

-- Comentários da obra, sem os de capítulos: o índice por obra teria de percorrer todos eles para filtrar.
CREATE INDEX ix_comments_book_only ON comments (book_id, created_at)
    WHERE chapter_id IS NULL AND deleted_at IS NULL;
