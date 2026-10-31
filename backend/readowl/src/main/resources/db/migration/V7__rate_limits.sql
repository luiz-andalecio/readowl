CREATE TABLE rate_limit_buckets (
    bucket_key   VARCHAR(100) NOT NULL,
    window_start TIMESTAMPTZ  NOT NULL,
    hits         INT          NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (bucket_key, window_start)
);

CREATE INDEX ix_rate_limit_buckets_expires_at ON rate_limit_buckets (expires_at);
