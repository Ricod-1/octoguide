CREATE TABLE knowledge_category (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    icon VARCHAR(32),
    description VARCHAR(255),
    sort_order INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_auto BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE knowledge_item (
    id VARCHAR(96) PRIMARY KEY,
    category_id VARCHAR(64) NOT NULL,
    question VARCHAR(500) NOT NULL,
    tags VARCHAR(500),
    source_type VARCHAR(32) NOT NULL DEFAULT 'manual',
    source_url VARCHAR(1000),
    source_title VARCHAR(255),
    source_id VARCHAR(96),
    auto_entry BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_item_category FOREIGN KEY (category_id) REFERENCES knowledge_category(id)
);

CREATE TABLE knowledge_block (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id VARCHAR(96) NOT NULL,
    block_type VARCHAR(16) NOT NULL,
    content LONGVARCHAR NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_block_item FOREIGN KEY (item_id) REFERENCES knowledge_item(id) ON DELETE CASCADE
);

CREATE TABLE term (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    english VARCHAR(128) NOT NULL,
    chinese VARCHAR(128) NOT NULL,
    description VARCHAR(500) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_term_english UNIQUE (english)
);

CREATE TABLE knowledge_source (
    id VARCHAR(96) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    url VARCHAR(1000) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    category_id VARCHAR(64),
    tags VARCHAR(500),
    site_title VARCHAR(255),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
