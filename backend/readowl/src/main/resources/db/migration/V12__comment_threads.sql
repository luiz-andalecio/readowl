-- As listas passam a mostrar só comentários principais, inclusive os excluídos que ainda têm respostas.
DROP INDEX ix_comments_book_only;
CREATE INDEX ix_comments_book_threads ON comments (book_id, created_at) WHERE chapter_id IS NULL AND parent_id IS NULL;
CREATE INDEX ix_comments_chapter_threads ON comments (chapter_id, created_at)
    WHERE chapter_id IS NOT NULL AND parent_id IS NULL;

DROP INDEX ix_comments_parent;
CREATE INDEX ix_comments_parent_created ON comments (parent_id, created_at) WHERE parent_id IS NOT NULL;
