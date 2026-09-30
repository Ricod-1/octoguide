-- V6: 对齐分类名称与 Node 版，添加自动采集所需分类，并 seed 数据源配置。

-- 更新现有分类名称以对齐 Node 版 data/sources.js 中的 category 字段。
-- 手工条目的 category_id 外键不受名称更新影响。
UPDATE knowledge_category SET name = '认识 GitHub' WHERE id = 'intro';
UPDATE knowledge_category SET name = '仓库操作' WHERE id = 'repo';

-- 添加自动采集所需的新分类。
INSERT INTO knowledge_category (id, name, icon, description, sort_order, enabled) VALUES
('trending', '趋势仓库', '🔥', '每日热门仓库自动采集', 8, TRUE),
('changelog', '新功能动态', '📣', 'GitHub 官方更新日志自动采集', 9, TRUE),
('auto-collected', '自动采集', '🤖', '未匹配到分类的自动采集条目', 99, TRUE)
ON DUPLICATE KEY UPDATE name=VALUES(name), description=VALUES(description), sort_order=VALUES(sort_order);

-- Seed 数据源配置（与 Node 版 data/sources.js 对齐）。
-- category_id 映射：认识 GitHub→intro, 仓库操作→repo, 提交与版本→commit,
-- 协作→collab, 自动化与配置→auto, 趋势仓库→trending, 新功能动态→changelog。
INSERT INTO knowledge_source (id, title, url, source_type, category_id, tags, site_title, enabled) VALUES
('hello-world', 'Hello World（新手第一次使用 GitHub）',
 'https://docs.github.com/zh/get-started/start-your-journey/hello-world',
 'docs', 'intro', '新手,入门,github', 'GitHub 官方中文文档', TRUE),
('git-and-github-learning-resources', 'Git 和 GitHub 学习资源',
 'https://docs.github.com/zh/get-started/start-your-journey/git-and-github-learning-resources',
 'docs', 'intro', '新手,学习,git', 'GitHub 官方中文文档', TRUE),
('quickstart-repos', '仓库快速入门（创建与管理仓库）',
 'https://docs.github.com/zh/repositories/creating-and-managing-repositories/quickstart-for-repositories',
 'docs', 'repo', '仓库,创建,repository', 'GitHub 官方中文文档', TRUE),
('about-remote-repositories', '远程仓库（clone / push / pull）',
 'https://docs.github.com/zh/get-started/getting-started-with-git/about-remote-repositories',
 'docs', 'repo', '仓库,远程,clone,push,pull', 'GitHub 官方中文文档', TRUE),
('pushing-commits', '推送提交到远程仓库',
 'https://docs.github.com/zh/get-started/using-git/pushing-commits-to-a-remote-repository',
 'docs', 'commit', '提交,push,远程', 'GitHub 官方中文文档', TRUE),
('about-pull-requests', '关于拉取请求（PR）',
 'https://docs.github.com/zh/pull-requests/collaborating-with-pull-requests/proposing-changes-to-your-work-with-pull-requests/about-pull-requests',
 'docs', 'collab', '协作,pr,pull request', 'GitHub 官方中文文档', TRUE),
('about-issues', '关于议题（Issue）',
 'https://docs.github.com/zh/issues/tracking-your-work-with-issues/about-issues',
 'docs', 'collab', '协作,issue,问题', 'GitHub 官方中文文档', TRUE),
('about-actions', '关于 GitHub Actions（自动化）',
 'https://docs.github.com/zh/actions/about-github-actions/understanding-github-actions',
 'docs', 'auto', '自动化,actions,ci', 'GitHub 官方中文文档', TRUE),
('connecting-with-ssh', '通过 SSH 连接到 GitHub',
 'https://docs.github.com/zh/authentication/connecting-to-github-with-ssh/about-ssh',
 'docs', 'auto', 'ssh,密钥,配置', 'GitHub 官方中文文档', TRUE),
('readme-about', '关于 README',
 'https://docs.github.com/zh/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-readmes',
 'docs', 'repo', 'readme,说明,文档', 'GitHub 官方中文文档', TRUE),
('github-trending', 'GitHub Trending 每日热门仓库',
 'https://github.com/trending?since=daily',
 'trending', 'trending', '趋势,热门,排行榜', 'GitHub Trending', TRUE),
('github-changelog', 'GitHub 官方更新日志（Changelog）',
 'https://github.blog/changelog/feed/',
 'rss', 'changelog', '新功能,更新,changelog', 'GitHub Changelog', TRUE)
ON DUPLICATE KEY UPDATE title=VALUES(title), url=VALUES(url), source_type=VALUES(source_type),
category_id=VALUES(category_id), tags=VALUES(tags), site_title=VALUES(site_title);
