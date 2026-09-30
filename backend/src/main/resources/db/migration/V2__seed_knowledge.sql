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
