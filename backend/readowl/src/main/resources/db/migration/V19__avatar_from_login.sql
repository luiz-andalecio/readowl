-- Só a foto que veio do login acompanha as mudanças feitas no provedor. Uma imagem escolhida pela pessoa não é substituída.
ALTER TABLE users ADD COLUMN avatar_from_login BOOLEAN NOT NULL DEFAULT false;

UPDATE users SET avatar_from_login = true WHERE avatar_url IS NOT NULL AND avatar_key IS NULL;
