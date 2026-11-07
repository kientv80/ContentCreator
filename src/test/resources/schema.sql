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

CREATE TABLE IF NOT EXISTS news_story_extra (
    summary_id  CHAR(36) NOT NULL REFERENCES news_summary(id) ON DELETE CASCADE,
    story_index INT NOT NULL,
    story_title VARCHAR(300) NOT NULL,
    key_points  TEXT NULL,
    analysis    TEXT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (summary_id, story_index)
);

CREATE TABLE IF NOT EXISTS news_story_source (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    country_code VARCHAR(2) NOT NULL,
    url_hash     CHAR(64) NOT NULL,
    url          VARCHAR(2000) NOT NULL,
    summary_id   CHAR(36) NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_news_story_source_summary FOREIGN KEY (summary_id) REFERENCES news_summary (id) ON DELETE SET NULL,
    CONSTRAINT uq_news_story_source_url UNIQUE (country_code, url_hash)
);

-- ElderEase V10 (country) and V62/V63 (AI news collection), only the columns ContentCreator uses.
CREATE TABLE IF NOT EXISTS country (
    code             VARCHAR(2) PRIMARY KEY,
    name             VARCHAR(80) NOT NULL,
    default_language VARCHAR(8) NOT NULL DEFAULT 'en',
    enabled          BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS country_news_config (
    country_code VARCHAR(2) PRIMARY KEY REFERENCES country (code),
    collect      BOOLEAN NOT NULL DEFAULT FALSE,
    language     VARCHAR(8) NOT NULL,
    notes        VARCHAR(1000) NULL,
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS country_news_category (
    country_code  VARCHAR(2) NOT NULL REFERENCES country (code),
    category_code VARCHAR(20) NOT NULL REFERENCES news_category (code),
    PRIMARY KEY (country_code, category_code)
);

CREATE TABLE IF NOT EXISTS country_news_site (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    country_code VARCHAR(2) NOT NULL REFERENCES country (code),
    name         VARCHAR(80) NOT NULL,
    domain       VARCHAR(120) NOT NULL,
    sort_order   INT NOT NULL,
    CONSTRAINT uq_country_news_site_domain UNIQUE (country_code, domain)
);

CREATE TABLE IF NOT EXISTS news_collection_settings (
    id                   TINYINT PRIMARY KEY,
    prompt_template      VARCHAR(4000) NOT NULL,
    stories_per_category INT NOT NULL DEFAULT 3,
    calls_per_day        INT NOT NULL DEFAULT 300,
    updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_news_collection_settings_single CHECK (id = 1)
);

CREATE TABLE IF NOT EXISTS news_collection_run (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    started_at    DATETIME(6) NOT NULL,
    country_code  VARCHAR(2) NOT NULL,
    category_code VARCHAR(20) NOT NULL,
    stories       INT NOT NULL DEFAULT 0,
    stored_count  INT NOT NULL DEFAULT 0,
    rejected      INT NOT NULL DEFAULT 0,
    failed        BOOLEAN NOT NULL DEFAULT FALSE,
    error         VARCHAR(120) NULL,
    duration_ms   INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS news_collection_secret (
    id         TINYINT PRIMARY KEY,
    ciphertext VARBINARY(512) NOT NULL,
    iv         VARBINARY(12) NOT NULL,
    last4      VARCHAR(4) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_news_collection_secret_single CHECK (id = 1)
);
