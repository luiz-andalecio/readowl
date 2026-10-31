ALTER TABLE users DROP CONSTRAINT ck_users_role;
ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'MODERATOR', 'ADMIN', 'ROOT'));

-- Sem valor padrão: até esta versão nenhum endpoint criava obras. Se houver linhas, a migration falha sem alterar nada.
ALTER TABLE books ADD COLUMN acronym VARCHAR(10) NOT NULL;
ALTER TABLE books ADD CONSTRAINT ck_books_acronym CHECK (acronym ~ '^[A-Z0-9]{2,10}$');

CREATE INDEX ix_tags_name_prefix ON tags (lower(name) text_pattern_ops);
