-- Pausado e hiato são situações diferentes para quem acompanha uma obra.
ALTER TABLE books DROP CONSTRAINT ck_books_status;
ALTER TABLE books ADD CONSTRAINT ck_books_status CHECK (status IN ('ONGOING', 'COMPLETED', 'HIATUS', 'PAUSED'));
