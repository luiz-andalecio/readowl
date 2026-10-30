CREATE TABLE banners (
    id           UUID PRIMARY KEY,
    version      BIGINT        NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL,
    name         VARCHAR(200)  NOT NULL,
    image_url    VARCHAR(1000) NOT NULL,
    link_url     VARCHAR(1000) NOT NULL,
    position     INT           NOT NULL,
    enabled      BOOLEAN       NOT NULL,
    active_from  TIMESTAMPTZ,
    active_until TIMESTAMPTZ,
    CONSTRAINT ck_banners_period CHECK (active_until IS NULL OR active_from IS NULL OR active_until > active_from)
);

CREATE INDEX ix_banners_enabled_position ON banners (position) WHERE enabled;
