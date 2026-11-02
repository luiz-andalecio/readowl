-- Arquivos que saíram de uso, mas são prova em uma denúncia aberta. Saem do armazenamento quando a última denúncia é encerrada.
CREATE TABLE held_image_files (
    file_key VARCHAR(200) PRIMARY KEY,
    held_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
