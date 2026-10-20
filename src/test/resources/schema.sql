CREATE TABLE IF NOT EXISTS news_summary (
    id           CHAR(36) PRIMARY KEY,
    headline     VARCHAR(120) NOT NULL,
    summary      VARCHAR(1000) NOT NULL,
    news_date    DATE NOT NULL,
    country_code VARCHAR(2) NULL,
    language     VARCHAR(2) NOT NULL,
    image_url    VARCHAR(512) NULL,
    audio_url    VARCHAR(512) NULL,
    published    BOOLEAN NOT NULL DEFAULT FALSE,
    published_at DATETIME(6) NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL
);
