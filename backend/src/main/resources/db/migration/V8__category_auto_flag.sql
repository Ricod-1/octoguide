-- V8: 统一统计口径——为分类增加 is_auto 标记，区分人工维护分类与自动采集分类。
-- 自动采集分类由 V6 引入（trending / changelog / auto-collected）。

ALTER TABLE knowledge_category ADD COLUMN is_auto BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE knowledge_category SET is_auto = TRUE WHERE id IN ('trending', 'changelog', 'auto-collected');
