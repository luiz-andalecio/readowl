-- A capa fica em outro site: guardamos só o endereço.
ALTER TABLE books ADD COLUMN cover_url VARCHAR(1000);

-- A foto de perfil fica no armazenamento de objetos. A chave serve para apagar, e o endereço, para mostrar.
ALTER TABLE users ADD COLUMN avatar_key VARCHAR(200);
ALTER TABLE users ADD COLUMN avatar_url VARCHAR(1000);

CREATE TABLE user_links (
    user_id  UUID          NOT NULL,
    position INT           NOT NULL,
    type     VARCHAR(20)   NOT NULL,
    url      VARCHAR(1000) NOT NULL,
    PRIMARY KEY (user_id, position),
    CONSTRAINT fk_user_links_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_links_type CHECK (type IN ('DISCORD', 'APOIA_SE', 'PATREON', 'KO_FI', 'INSTAGRAM', 'X', 'YOUTUBE',
                                                  'TIKTOK', 'FACEBOOK', 'BLUESKY', 'TWITCH', 'WEBSITE'))
);
