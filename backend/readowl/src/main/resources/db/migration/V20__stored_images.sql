-- Capas e banners passam a ficar no armazenamento. Sem chave, a imagem é um endereço externo salvo antes desta mudança,
-- que continua valendo até ser trocado.
ALTER TABLE books ADD COLUMN cover_key VARCHAR(200);
ALTER TABLE books ADD COLUMN banner_key VARCHAR(200);
ALTER TABLE books ADD COLUMN banner_url VARCHAR(1000);

-- O banner é criado antes da imagem, que tem rota própria. Sem imagem, ele não aparece na página inicial.
ALTER TABLE banners ADD COLUMN image_key VARCHAR(200);
ALTER TABLE banners ALTER COLUMN image_url DROP NOT NULL;

ALTER TABLE comments ADD COLUMN image_url VARCHAR(1000);
