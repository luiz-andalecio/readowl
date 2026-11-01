-- Dados sintéticos para o teste de carga, em um banco próprio: nunca rode em um banco com dados reais.
--
--   psql -v accounts=20000 -v authors=500 -v books=5000 -v chapters_per_book=10 -f seed.sql
--
-- O texto dos capítulos repete um trecho, então comprime melhor que um texto real e ocupa menos disco.
\set ON_ERROR_STOP on

DO $$
BEGIN
    IF current_database() NOT LIKE '%\_test' THEN
        RAISE EXCEPTION 'Recusado: o banco % não termina em _test', current_database();
    END IF;
END
$$;

BEGIN;

-- Todas as contas entram com a senha da conta "modelo", criada pela API para ter um hash de verdade.
INSERT INTO users (id, version, created_at, updated_at, name, username, email, password_hash, role, status,
                   email_verified_at, email_new_chapters, terms_version, terms_accepted_at)
SELECT uuidv7(), 0, now(), now(), 'Leitor ' || g, 'carga-' || g, 'carga' || g || '@example.com', model.password_hash, 'USER',
       'ACTIVE', now(), false, model.terms_version, now()
FROM generate_series(1, :accounts) g
CROSS JOIN (SELECT password_hash, terms_version FROM users WHERE username = 'carga-modelo') model;

CREATE TEMP TABLE seed_books ON COMMIT DROP AS
SELECT uuidv7() AS id, g AS n,
       (SELECT id FROM users WHERE username = 'carga-' || (1 + g % :authors)) AS author_id
FROM generate_series(1, :books) g;

INSERT INTO books (id, version, created_at, updated_at, title, acronym, slug, synopsis, status, publication_state,
                   view_count, author_id, last_chapter_published_at)
SELECT id, 0, now() - n * interval '1 minute', now(), 'Obra de carga ' || n || ' ' || md5(n::text),
       'C' || n, 'c' || n || '-obra-de-carga-' || n, 'Uma história de teste número ' || n || '. ' || repeat('Sinopse longa. ', 30),
       'ONGOING', 'PUBLISHED', n, author_id, now() - n * interval '1 minute'
FROM seed_books;

INSERT INTO book_genres (book_id, genre_id)
SELECT b.id, g.id
FROM seed_books b
CROSS JOIN LATERAL (SELECT id FROM genres ORDER BY id OFFSET (b.n % (SELECT count(*) FROM genres)) LIMIT 1) g;

CREATE TEMP TABLE seed_chapters ON COMMIT DROP AS
SELECT uuidv7() AS id, uuidv7() AS revision_id, b.id AS book_id, b.author_id, c AS n
FROM seed_books b
CROSS JOIN generate_series(1, :chapters_per_book) c;

INSERT INTO chapters (id, version, created_at, updated_at, title, slug, state, display_order, book_id)
SELECT id, 0, now(), now(), 'Capítulo ' || n, 'capitulo-' || n, 'DRAFT', n, book_id
FROM seed_chapters;

INSERT INTO chapter_drafts (chapter_id, version, document, format_version, updated_at)
SELECT id, 0, '{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Texto do capítulo."}]}]}', 1, now()
FROM seed_chapters;

-- Cerca de 8 KB de texto por capítulo.
INSERT INTO chapter_revisions (id, chapter_id, revision_number, document, format_version, plain_text, html, created_by, created_at)
SELECT revision_id, id, 1,
       '{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Texto do capítulo."}]}]}', 1,
       repeat(md5(id::text) || ' era uma vez uma história. ', 140),
       '<p>' || repeat(md5(id::text) || ' era uma vez uma história. ', 140) || '</p>',
       author_id, now()
FROM seed_chapters;

UPDATE chapters c SET state = 'PUBLISHED', published_revision_id = s.revision_id
FROM seed_chapters s WHERE s.id = c.id;

COMMIT;

-- A obra mais recente, c1, é a popular: tem muitos seguidores e comentários, como no lançamento de um capítulo.
BEGIN;

INSERT INTO book_follows (user_id, book_id, created_at)
SELECT u.id, b.id, now()
FROM users u
CROSS JOIN (SELECT id FROM books WHERE slug = 'c1-obra-de-carga-1') b
WHERE u.username LIKE 'carga-%' AND u.username <> 'carga-modelo';

INSERT INTO comments (id, version, created_at, updated_at, content, user_id, book_id)
SELECT uuidv7(), 0, now() - g * interval '1 minute', now(), 'Comentário de carga número ' || g,
       (SELECT id FROM users WHERE username = 'carga-' || (1 + g % :accounts)), b.id
FROM generate_series(1, 100000) g
CROSS JOIN (SELECT id FROM books WHERE slug = 'c1-obra-de-carga-1') b;

-- Alguns comentários e seguidores em cada obra.
INSERT INTO comments (id, version, created_at, updated_at, content, user_id, book_id)
SELECT uuidv7(), 0, now(), now(), 'Comentário ' || c || ' da obra ' || b.n, b.author_id, b.id
FROM (SELECT id, author_id, row_number() OVER () AS n FROM books WHERE slug <> 'c1-obra-de-carga-1') b
CROSS JOIN generate_series(1, 10) c;

COMMIT;

ANALYZE;
