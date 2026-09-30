-- OctoGuide 数据库初始化脚本（MySQL 8，容器首次创建数据卷时由 mysql 官方镜像自动执行）。
--
-- 内容与后端 Flyway 迁移 V1~V8 完全一致，由下列脚本按顺序合并生成，请勿手工改动其中任一语句：
--   V1__create_schema.sql / V2__seed_knowledge.sql / V3__align_text_columns.sql
--   V4__expand_manual_knowledge.sql / V5__add_source_id_to_knowledge_item.sql
--   V6__seed_knowledge_sources.sql / V7__unify_category_icons.sql / V8__category_auto_flag.sql
--
-- 与 Flyway 的关系：本脚本负责"空库一次到位"，因此额外写入 flyway_schema_history 基线记录，
-- 使后端启动时 Flyway 将 V1~V8 视为已执行，不会重复建表或重复插入种子数据。
-- 库名由 compose 的 MYSQL_DATABASE 创建，本脚本不执行 CREATE DATABASE。

SET NAMES utf8mb4;

-- ============================================================================
-- V1__create_schema.sql
-- ============================================================================
CREATE TABLE IF NOT EXISTS knowledge_category (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  icon VARCHAR(32),
  description VARCHAR(255),
  sort_order INT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_item (
  id VARCHAR(96) PRIMARY KEY,
  category_id VARCHAR(64) NOT NULL,
  question VARCHAR(500) NOT NULL,
  tags VARCHAR(500),
  source_type VARCHAR(32) NOT NULL DEFAULT 'manual',
  source_url VARCHAR(1000),
  source_title VARCHAR(255),
  auto_entry BOOLEAN NOT NULL DEFAULT FALSE,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_item_category FOREIGN KEY (category_id) REFERENCES knowledge_category(id),
  UNIQUE KEY uk_item_question (category_id, question(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_block (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  item_id VARCHAR(96) NOT NULL,
  block_type VARCHAR(16) NOT NULL,
  content TEXT NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_block_item FOREIGN KEY (item_id) REFERENCES knowledge_item(id) ON DELETE CASCADE,
  KEY idx_block_item (item_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS term (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  english VARCHAR(128) NOT NULL,
  chinese VARCHAR(128) NOT NULL,
  description VARCHAR(500) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_term_english (english)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_source (
  id VARCHAR(96) PRIMARY KEY,
  title VARCHAR(255) NOT NULL,
  url VARCHAR(1000) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  category_id VARCHAR(64),
  tags VARCHAR(500),
  site_title VARCHAR(255),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS conversation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(255),
  status VARCHAR(32) NOT NULL DEFAULT 'active',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS conversation_message (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT NOT NULL,
  role_name VARCHAR(16) NOT NULL,
  content LONGTEXT NOT NULL,
  mode_name VARCHAR(32),
  sources_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================================
-- V2__seed_knowledge.sql
-- ============================================================================
INSERT INTO knowledge_category (id, name, icon, description, sort_order) VALUES
('intro', 'GitHub 入门', '◎', '认识 GitHub、Git 和开源协作', 1),
('repo', '仓库基础', '□', '创建、克隆、上传和同步仓库', 2),
('commit', '提交与版本', '▣', 'add / commit / log / 回退', 3),
('branch', '分支', '⑂', '分支的创建、切换与合并', 4),
('collab', '协作', '♧', 'Fork / PR / Issue / Star', 5),
('auto', '自动化与配置', '⚙', 'Actions / SSH / README', 6),
('errors', '常见报错', '!', '推送失败、SSH 和冲突', 7)
ON DUPLICATE KEY UPDATE name=VALUES(name), description=VALUES(description), sort_order=VALUES(sort_order);

INSERT INTO knowledge_item (id, category_id, question, tags, source_type) VALUES
('intro-1','intro','GitHub 到底是什么？','GitHub,入门,概念','manual'),
('repo-1','repo','怎么创建一个 GitHub 仓库？','仓库,创建','manual'),
('repo-3','repo','怎么把本地代码上传到 GitHub？','仓库,上传,push','manual'),
('commit-1','commit','git add 和 git commit 到底是什么意思？','提交,版本','manual'),
('branch-1','branch','什么是分支（Branch）？','分支,branch','manual'),
('collab-2','collab','什么是 Pull Request（PR）？','协作,PR,合并请求','manual'),
('collab-3','collab','什么是 Issue？','协作,Issue,问题单','manual'),
('auto-1','auto','什么是 GitHub Actions？','自动化,Actions','manual'),
('auto-2','auto','SSH 是什么？怎么配置？','配置,SSH,密钥','manual'),
('errors-1','errors','推送失败：rejected / failed to push 怎么办？','报错,push,失败','manual'),
('errors-3','errors','合并时出现 CONFLICT（冲突）怎么办？','报错,冲突','manual')
ON DUPLICATE KEY UPDATE question=VALUES(question), tags=VALUES(tags);

INSERT INTO knowledge_block (item_id, block_type, content, sort_order) VALUES
('intro-1','text','GitHub 是一个在线托管 Git 仓库的平台，可以保存代码、记录版本，并和其他开发者协作。Git 是本地版本控制工具，GitHub 是把仓库放到网上并提供协作功能的服务。',1),
('repo-1','text','登录 GitHub 后点击右上角加号，选择 New repository，填写仓库名称和说明，选择公开或私有后创建。',1),
('repo-3','text','在项目文件夹打开终端，先初始化 Git，再提交并绑定远程仓库。',1),
('repo-3','code','git init\ngit add .\ngit commit -m "第一次提交"\ngit branch -M main\ngit remote add origin https://github.com/用户名/仓库名.git\ngit push -u origin main',2),
('repo-3','tip','第一次推送前确认远程地址和分支名称正确。',3),
('commit-1','text','git add 把改动放入暂存区，git commit 把暂存区内容保存成一个版本快照。',1),
('commit-1','code','git status\ngit add 文件名\ngit commit -m "说明这次改动"',2),
('branch-1','text','分支是从主线分出的平行开发线。你可以在分支上开发功能，完成后再合并回 main。',1),
('branch-1','code','git branch 新功能\ngit switch 新功能\ngit switch main\ngit merge 新功能',2),
('collab-2','text','Pull Request（PR）是向原仓库作者请求合并改动的协作流程。常见步骤是 Fork、修改、push 到自己的副本，再在原仓库创建 PR。',1),
('collab-2','tip','提交 PR 时写清楚改了什么、为什么改，以及如何验证。',2),
('collab-3','text','Issue 是项目里的问题单或任务单，用来报告 Bug、提出功能需求和讨论问题。',1),
('auto-1','text','GitHub Actions 是 GitHub 自带的自动化能力，可以在 push 或 PR 时自动运行测试、构建和部署。配置文件通常放在 .github/workflows 目录。',1),
('auto-2','text','SSH 是一种安全连接方式，配置后 push 和 pull 不必反复输入密码。需要生成密钥并把公钥添加到 GitHub。',1),
('auto-2','code','ssh-keygen -t ed25519 -C "你的邮箱"\ncat ~/.ssh/id_ed25519.pub',2),
('errors-1','text','远程仓库有本地没有的提交时，直接 push 会被拒绝。先拉取远程更新，再解决可能的冲突，最后重新 push。',1),
('errors-1','code','git pull origin main\ngit push origin main',2),
('errors-3','text','冲突表示两边修改了同一处代码。打开冲突文件，处理 <<<<<<<、=======、>>>>>>> 标记，确认内容后重新 add 和 commit。',1),
('errors-3','code','git add 冲突的文件\ngit commit -m "解决冲突"',2)
ON DUPLICATE KEY UPDATE content=VALUES(content), block_type=VALUES(block_type), sort_order=VALUES(sort_order);

INSERT INTO term (english, chinese, description, sort_order) VALUES
('Repository','仓库','装代码的项目文件夹，GitHub 上一切协作的基础。',1),
('Commit','提交','一次代码版本的存档。',2),
('Branch','分支','从主线上分出的平行开发线。',3),
('Pull Request','合并请求','向项目作者申请合并你的改动，简称 PR。',4),
('Fork','复制','把别人的仓库复制一份到自己的账号下。',5),
('Issue','问题单','报告 Bug、提需求和讨论问题的地方。',6),
('Star','收藏','给项目点赞或收藏。',7),
('Clone','克隆','把远程仓库下载到本地。',8),
('Push','推送','把本地代码上传到远程仓库。',9),
('Pull','拉取','把远程更新下载到本地。',10),
('Merge','合并','把分支或改动合到一起。',11),
('Conflict','冲突','两边改了同一处代码，需要手动选择。',12),
('README','说明文件','仓库首页展示的项目说明书。',13),
('Actions','自动化','GitHub 内置的自动化任务机器人。',14),
('SSH','安全连接','免密码安全连接 GitHub 的方式。',15),
('Markdown','标记语言','写 README、Issue 的轻量排版语法。',16),
('Release','发布','正式发布一个版本，带下载包。',17),
('Gitignore','忽略清单','列出不需要上传的文件，例如密钥和缓存。',18)
ON DUPLICATE KEY UPDATE chinese=VALUES(chinese), description=VALUES(description), sort_order=VALUES(sort_order);

-- ============================================================================
-- V3__align_text_columns.sql
-- ============================================================================
ALTER TABLE knowledge_block MODIFY content LONGTEXT NOT NULL;

-- ============================================================================
-- V4__expand_manual_knowledge.sql
-- ============================================================================
INSERT INTO knowledge_item (id, category_id, question, tags, source_type) VALUES
('repo-2','repo','怎么把 GitHub 上的项目下载到本地？','仓库,clone,克隆','manual'),
('repo-4','repo','怎么把 GitHub 上的更新拉到本地？','仓库,pull,同步','manual'),
('commit-2','commit','怎么查看项目的修改历史？','版本,log,历史','manual'),
('commit-3','commit','改错了，怎么回到之前的版本？','版本,回退,revert','manual'),
('branch-2','branch','怎么把分支合并回主分支？','分支,合并,merge','manual'),
('collab-1','collab','Star、Fork、Watch 有什么区别？','协作,star,fork,watch','manual'),
('auto-3','auto','README 文件是干什么的？','配置,README,说明','manual'),
('errors-2','errors','提示 Permission denied (publickey) 怎么办？','报错,SSH,publickey','manual')
ON DUPLICATE KEY UPDATE question=VALUES(question), tags=VALUES(tags);

INSERT INTO knowledge_block (item_id, block_type, content, sort_order) VALUES
('repo-2','code','git clone https://github.com/用户名/仓库名.git\ncd 仓库名',1),
('repo-2','text','clone 会把远程仓库复制到电脑上，并自动配置 origin 远程地址。',2),
('repo-4','code','git pull origin main',1),
('repo-4','text','推荐每次开始写代码前先 pull 一次，避免和别人的改动冲突。',2),
('commit-2','code','git log --oneline',1),
('commit-2','text','每条记录都有提交号，以后想回到某个版本就靠它。',2),
('commit-3','text','还没 commit 可以丢弃改动；已经 push 的提交建议使用 revert 生成一条撤销记录。',1),
('commit-3','code','git reset --soft HEAD~1\ngit revert 提交号',2),
('branch-2','code','git switch main\ngit merge 新功能名',1),
('branch-2','text','如果出现 CONFLICT，需要解决冲突后再 add 和 commit。',2),
('collab-1','text','Star 是收藏，Fork 是复制到自己账号，Watch 是订阅动态通知。Fork 会生成属于你的副本。',1),
('auto-3','text','README 是仓库的说明书，通常写项目用途、安装和使用方法，GitHub 会自动展示在仓库首页。',1),
('errors-2','text','原因通常是 SSH 公钥没有添加到 GitHub。可以重新生成密钥并添加，或者改用 HTTPS 地址。',1),
('errors-2','code','ssh-keygen -t ed25519 -C "你的邮箱"\ncat ~/.ssh/id_ed25519.pub',2)
ON DUPLICATE KEY UPDATE content=VALUES(content), block_type=VALUES(block_type), sort_order=VALUES(sort_order);

-- ============================================================================
-- V5__add_source_id_to_knowledge_item.sql
-- ============================================================================
-- 为 knowledge_item 添加 source_id 字段，用于按数据源重建自动采集条目。
-- 手工条目的 source_id 为 NULL，自动条目记录其来源数据源 ID。
ALTER TABLE knowledge_item
    ADD COLUMN source_id VARCHAR(96) NULL AFTER source_title;

-- 按数据源查询/删除自动条目时使用的索引。
CREATE INDEX idx_item_source_id ON knowledge_item(source_id);

-- ============================================================================
-- V6__seed_knowledge_sources.sql
-- ============================================================================
-- 对齐分类名称与 Node 版，添加自动采集所需分类，并 seed 数据源配置。

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

-- ============================================================================
-- V7__unify_category_icons.sql
-- ============================================================================
-- 统一分类图标为 emoji，避免几何符号（□ / ◎ / ⑂ 等）在部分系统上显示为“豆腐块”，
-- 并与自动采集分类（V6）风格一致。
UPDATE knowledge_category SET icon = '🐙' WHERE id = 'intro';
UPDATE knowledge_category SET icon = '📦' WHERE id = 'repo';
UPDATE knowledge_category SET icon = '✅' WHERE id = 'commit';
UPDATE knowledge_category SET icon = '🌿' WHERE id = 'branch';
UPDATE knowledge_category SET icon = '🤝' WHERE id = 'collab';
UPDATE knowledge_category SET icon = '⚙️' WHERE id = 'auto';
UPDATE knowledge_category SET icon = '⚠️' WHERE id = 'errors';

-- ============================================================================
-- V8__category_auto_flag.sql
-- ============================================================================
-- 统一统计口径——为分类增加 is_auto 标记，区分人工维护分类与自动采集分类。
-- 自动采集分类由 V6 引入（trending / changelog / auto-collected）。
ALTER TABLE knowledge_category ADD COLUMN is_auto BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE knowledge_category SET is_auto = TRUE WHERE id IN ('trending', 'changelog', 'auto-collected');

-- ============================================================================
-- Flyway 基线：把 V1~V8 标记为已应用，避免后端启动时重复执行上面这些语句。
-- 表结构与 flyway-core 8.5.13 的建表语句一致（含 MySQL 8 的 utf8mb4_0900_ai_ci 排序规则）。
-- 说明：数据库由 mysql 镜像以 MYSQL_DATABASE=octoguide 创建，当前 schema 即 octoguide，
-- 与 Flyway 默认的 defaultSchema 一致，因此无需显式指定 schema。
-- ============================================================================
CREATE TABLE IF NOT EXISTS flyway_schema_history (
    installed_rank INT NOT NULL,
    version VARCHAR(50),
    description VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL,
    script VARCHAR(1000) NOT NULL,
    checksum INT,
    installed_by VARCHAR(100) NOT NULL,
    installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    execution_time INT NOT NULL,
    success BOOLEAN NOT NULL,
    CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank)
) ENGINE=InnoDB;

CREATE INDEX flyway_schema_history_s_idx ON flyway_schema_history (success);

-- checksum 由 Flyway 8.5.13 自身的 ChecksumCalculator 计算（逐行去 BOM 后按 UTF-8 字节累加 CRC32，
-- 不含行尾换行符），已用该内部方法逐条比对确认；若不一致 Flyway 会报 "Migration checksum mismatch"。
INSERT INTO flyway_schema_history
    (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
VALUES
    (1, '1', 'create schema',            'SQL', 'V1__create_schema.sql',              1289071414, 'octoguide', 0, TRUE),
    (2, '2', 'seed knowledge',           'SQL', 'V2__seed_knowledge.sql',             1561965280, 'octoguide', 0, TRUE),
    (3, '3', 'align text columns',       'SQL', 'V3__align_text_columns.sql',          663216552, 'octoguide', 0, TRUE),
    (4, '4', 'expand manual knowledge',  'SQL', 'V4__expand_manual_knowledge.sql',   -1715457214, 'octoguide', 0, TRUE),
    (5, '5', 'add source id to knowledge item', 'SQL', 'V5__add_source_id_to_knowledge_item.sql', -1344334373, 'octoguide', 0, TRUE),
    (6, '6', 'seed knowledge sources',   'SQL', 'V6__seed_knowledge_sources.sql',     2024478850, 'octoguide', 0, TRUE),
    (7, '7', 'unify category icons',     'SQL', 'V7__unify_category_icons.sql',       -888249018, 'octoguide', 0, TRUE),
    (8, '8', 'category auto flag',       'SQL', 'V8__category_auto_flag.sql',         -338225880, 'octoguide', 0, TRUE);
