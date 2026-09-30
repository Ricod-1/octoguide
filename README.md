# OctoGuide Java 版

这是 GitHub 操作智能助手的迁移版后端：Spring Boot 2.7 + MySQL 8。后端把原 Node.js 的知识检索逻辑迁移为 Java Service（本仓库当前只包含 backend，未包含前端工程）。

## 已完成能力

- 本地 MySQL 数据库 `octoguide`，Flyway 自动建表和导入 GitHub 知识/术语。
- Spring Boot API：分类、术语、状态、同步问答、NDJSON 流式聊天、知识记忆和手动更新。
- Java 版关键词检索：中文/英文归一化、GitHub 术语同义词和 IDF 排序。
- 可选 OpenAI-compatible LLM：设置 `OCTOGUIDE_LLM_KEY` 后，聊天接口会使用模型回答；未配置时自动回退到本地知识库。
- AI 联网研究：时效性问题（今天/最新/趋势等）自动判断知识库不足，AI 认路 → 抓取 → 必应兜底，抓到的网页并入回答上下文，回答底部附来源。
- 知识沉淀：联网回答后可点击「记下/不记」，确认后写入知识库，重复问题自动去重。

## 启动

MySQL 服务需要先运行。项目会使用独立数据库，不会操作其他库：

```powershell
& 'D:\programli\mysql-8.0.34-winx64\bin\mysql.exe' -uroot -p -e "CREATE DATABASE IF NOT EXISTS octoguide CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
```

后端（Java 8 + 本机 Maven）：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk1.8.0_192'
& 'D:\programli\Java web\java\apache-maven-3.6.3\bin\mvn.cmd' -f '.\backend\pom.xml' spring-boot:run -Dspring-boot.run.profiles=local
```

### 在 IDEA 中启动后端

打开 `Run | Edit Configurations...`，选择后端的 `OctoGuideApplication` 运行配置，并设置：

- `Active profiles`：`local`
- `Environment variables`：`OCTOGUIDE_DB_PASSWORD=你的 MySQL root 密码`

如果数据库不是默认地址或账号，再补充 `OCTOGUIDE_DB_URL`、`OCTOGUIDE_DB_USERNAME`。密码未设置时，启动日志会出现 `using password: NO`；这表示 IDEA 没有把环境变量传入，不是 Flyway 迁移脚本本身失败。

后端 API 为 `http://localhost:8080`。

## API

- `GET /api/categories`
- `GET /api/terms`
- `GET /api/status`
- `POST /api/ask`，请求体 `{ "question": "什么是 PR？" }`
- `POST /api/chat`，响应为 `application/x-ndjson`，事件为 `kb` 或 `progress? -> start -> delta* -> done`；AI 调用失败时为 `start -> error`，空回答会发送默认 `delta`
- `POST /api/remember`，请求体 `{ "question", "answer", "sourceUrl", "sourceTitle" }`
- `POST /api/knowledge/update`

其中知识更新接口需要请求头 `X-Knowledge-Update-Token`，令牌由环境变量 `OCTOGUIDE_KNOWLEDGE_UPDATE_TOKEN` 配置。

## 配置 LLM

不要把密钥写入前端。使用环境变量：

```powershell
$env:OCTOGUIDE_LLM_KEY='你的 API Key'
$env:OCTOGUIDE_LLM_BASE_URL='https://api.openai.com/v1'
$env:OCTOGUIDE_LLM_MODEL='gpt-4o-mini'
```

`OCTOGUIDE_LLM_BASE_URL` 可以填写服务根地址、带 `/v1` 的地址，或完整的 `/v1/chat/completions` 地址，程序会自动规范化。为兼容旧 Node 版配置，`OCTOGUIDE_LLM_URL` 也可以作为地址变量使用（优先级低于 `OCTOGUIDE_LLM_BASE_URL`）。

本地数据库密码、LLM Key 和知识更新令牌都通过环境变量注入，避免写入配置文件：
`OCTOGUIDE_DB_PASSWORD`、`OCTOGUIDE_LLM_KEY`、`OCTOGUIDE_KNOWLEDGE_UPDATE_TOKEN`。

可选开关：`OCTOGUIDE_CHAT_EMITTER_TIMEOUT_SECONDS`（流式响应超时，默认 300 秒）、`OCTOGUIDE_WEB_RESEARCH_ENABLED`（联网研究开关，默认开启，影响 `/api/status` 的 `webResearchEnabled`）、`OCTOGUIDE_CHAT_MAX_QUESTION_CHARS`（问题长度硬上限，默认 4000 字）、`OCTOGUIDE_CHAT_MAX_HISTORY_MESSAGES`（历史消息条数硬上限，默认 50 条）、`OCTOGUIDE_CHAT_MAX_HISTORY_MESSAGE_CHARS`（单条历史消息长度硬上限，默认 20000 字）。超出上限的请求会立即收到 `error` 事件，不会进入聊天执行线程。

## 验证

```powershell
# 后端：运行全部单元测试，并生成 JaCoCo 覆盖率报告
& 'D:\programli\Java web\java\apache-maven-3.6.3\bin\mvn.cmd' -f '.\backend\pom.xml' test
# 覆盖率报告位于 backend\target\site\jacoco\index.html

> 说明：后端已接入 JaCoCo 覆盖率度量（见 pom.xml）。因本项目所在路径含中文字符，javaagent 无法直接解析含中文的 destfile 路径，故覆盖率数据文件固定输出到系统临时目录 `%TEMP%\octoguide-jacoco.exec`，HTML 报告仍生成在 `backend\target\site\jacoco\`。
```
