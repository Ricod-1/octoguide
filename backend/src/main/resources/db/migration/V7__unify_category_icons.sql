-- V7: 统一分类图标为 emoji，避免几何符号（□ / ◎ / ⑂ 等）在部分系统上显示为“豆腐块”，并与自动采集分类（V6）风格一致。

UPDATE knowledge_category SET icon = '🐙' WHERE id = 'intro';
UPDATE knowledge_category SET icon = '📦' WHERE id = 'repo';
UPDATE knowledge_category SET icon = '✅' WHERE id = 'commit';
UPDATE knowledge_category SET icon = '🌿' WHERE id = 'branch';
UPDATE knowledge_category SET icon = '🤝' WHERE id = 'collab';
UPDATE knowledge_category SET icon = '⚙️' WHERE id = 'auto';
UPDATE knowledge_category SET icon = '⚠️' WHERE id = 'errors';