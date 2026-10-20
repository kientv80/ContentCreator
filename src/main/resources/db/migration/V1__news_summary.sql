CREATE TABLE news_summary (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title         VARCHAR(300) NOT NULL,
    summary       TEXT         NOT NULL,
    category      VARCHAR(60)  NULL,
    source_name   VARCHAR(120) NULL,
    source_url    VARCHAR(1000) NOT NULL,
    url_hash      CHAR(64)     NOT NULL,
    language      VARCHAR(10)  NOT NULL DEFAULT 'vi',
    published_at  TIMESTAMP    NULL,
    collected_at  TIMESTAMP    NOT NULL,
    CONSTRAINT uq_news_summary_url_hash UNIQUE (url_hash)
);
CREATE INDEX idx_news_summary_collected ON news_summary (collected_at);
