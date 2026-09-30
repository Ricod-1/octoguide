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
