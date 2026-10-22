CREATE TABLE IF NOT EXISTS news_summary (
    id           CHAR(36) PRIMARY KEY,
    headline     VARCHAR(120) NOT NULL,
    summary      VARCHAR(8000) NOT NULL,
    news_date    DATE NOT NULL,
    country_code VARCHAR(2) NULL,
    language     VARCHAR(2) NOT NULL,
    category     VARCHAR(20) NOT NULL DEFAULT 'OTHER',
    image_url    VARCHAR(512) NULL,
    audio_url    VARCHAR(512) NULL,
    published    BOOLEAN NOT NULL DEFAULT FALSE,
    published_at DATETIME(6) NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS news_category (
    code       VARCHAR(20) PRIMARY KEY,
    emoji      VARCHAR(16) NOT NULL,
    name_en    VARCHAR(60) NOT NULL,
    name_vi    VARCHAR(60) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    active     BOOLEAN NOT NULL DEFAULT TRUE
);
MERGE INTO news_category (code, emoji, name_en, name_vi, sort_order, active) KEY (code) VALUES
    ('DOMESTIC', '🏠', 'Domestic', 'Trong nước', 1, TRUE),
    ('WORLD', '🌍', 'World', 'Thế giới', 2, TRUE),
    ('STOCKS', '📈', 'Stocks', 'Chứng khoán', 3, TRUE),
    ('SCIENCE_TECH', '🔬', 'Science & tech', 'Khoa học & công nghệ', 4, TRUE),
    ('OTHER', '📰', 'Other', 'Khác', 5, TRUE);

CREATE TABLE IF NOT EXISTS users (
    id       CHAR(36) PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS poems (
    id               CHAR(36) PRIMARY KEY,
    writer_id        CHAR(36) NOT NULL REFERENCES users(id),
    content_text     VARCHAR(6000) NULL,
    voice_url        VARCHAR(500) NULL,
    voice_seconds    INT NULL,
    privacy          VARCHAR(12) NOT NULL,
    approval_status  VARCHAR(10) NOT NULL,
    rejection_reason VARCHAR(200) NULL,
    decided_at       DATETIME(6) NULL,
    local_date       DATE NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    deleted_at       DATETIME(6) NULL,
    CONSTRAINT chk_poems_privacy CHECK (privacy IN ('PRIVATE', 'CAREGIVERS', 'PUBLIC')),
    CONSTRAINT chk_poems_approval CHECK (approval_status IN ('NONE', 'PENDING', 'APPROVED', 'REJECTED'))
);
