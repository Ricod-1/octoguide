-- 为 knowledge_item 添加 source_id 字段，用于按数据源重建自动采集条目。
-- 手工条目的 source_id 为 NULL，自动条目记录其来源数据源 ID。
ALTER TABLE knowledge_item
    ADD COLUMN source_id VARCHAR(96) NULL AFTER source_title;

-- 按数据源查询/删除自动条目时使用的索引。
CREATE INDEX idx_item_source_id ON knowledge_item(source_id);
