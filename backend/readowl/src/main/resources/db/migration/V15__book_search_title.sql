-- Título sem acentos e em minúsculas, já calculado. A busca o lê em cada obra candidata, e calcular a cada vez era o maior
-- custo dela em palavras comuns: 200 ms contra 75 ms, em 5 mil obras.
ALTER TABLE books ADD COLUMN search_title TEXT GENERATED ALWAYS AS (immutable_unaccent(lower(title))) STORED;

CREATE INDEX ix_books_search_title_trgm ON books USING gin (search_title gin_trgm_ops);
DROP INDEX ix_books_title_trgm;
