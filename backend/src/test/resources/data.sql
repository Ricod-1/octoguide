INSERT INTO knowledge_category (id, name, icon, description, sort_order, enabled, is_auto)
VALUES
    ('intro', 'Getting Started', 'book', 'GitHub 入门知识', 1, TRUE, FALSE),
    ('auto-collected', '自动采集', '🤖', '默认回退分类', 99, TRUE, TRUE);

INSERT INTO knowledge_item (
    id, category_id, question, tags, source_type, source_url, source_title,
    auto_entry, enabled, updated_at
)
VALUES (
    'intro-1', 'intro', 'What is a repository?', 'repository,git', 'manual',
    NULL, NULL, FALSE, TRUE, CURRENT_TIMESTAMP
);

INSERT INTO knowledge_block (item_id, block_type, content, sort_order)
VALUES
    ('intro-1', 'text', 'A repository stores project files and their history.', 1),
    ('intro-1', 'code', 'git init', 2);

INSERT INTO term (english, chinese, description, sort_order)
VALUES ('Repository', '仓库', '用于存放项目文件及其版本历史。', 1);

-- KnowledgeUpdater 测试用分类和数据源
INSERT INTO knowledge_category (id, name, icon, description, sort_order, enabled, is_auto) VALUES
('test-cat', '认识 GitHub', '📘', '测试分类', 10, TRUE, FALSE);

INSERT INTO knowledge_source (id, title, url, source_type, category_id, tags, site_title, enabled) VALUES
('test-docs', '测试文档源', 'https://docs.example.com/test', 'docs', 'test-cat', '测试,入门', '测试站点', TRUE);
