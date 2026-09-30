# OctoGuide 项目文档索引

本目录（`docs/`）是本项目**唯一**的文档目录，所有设计文档、方案与实施计划都平铺存放在这里。

## 文档规范

| 项 | 要求 |
| --- | --- |
| 语言 | 一律**中文**（含正文与文件名） |
| 位置 | 统一写在 `docs/` 根目录，不新建 `specs/`、`plans/`、`superpowers/` 等子目录 |
| 命名 | `YYYY-MM-DD-主题.md`，如 `2026-09-30-前后端一键启动设计.md` |
| 索引 | 本文件 `docs/README.md` 为固定索引，不参与日期命名；新增文档后请在此登记 |

## 文档清单

| 日期 | 文档 | 类型 | 说明 | 状态 |
| --- | --- | --- | --- | --- |
| 2026-09-04 | [安全与稳定性修复实施计划](<2026-09-04-安全与稳定性修复实施计划.md>) | 实施计划 | 去除凭据泄露与不安全网络行为、给服务端工作加上边界、让知识写入幂等、恢复前后端验证（5 项任务） | 任务清单形式；**正文为英文（历史文档，待翻译）** |
| 2026-09-30 | [前后端一键启动设计](<2026-09-30-前后端一键启动设计.md>) | 设计文档 | 双重启入口 `start-all.cmd` + `start-all.ps1`、独立窗口实时看后端日志、`stop-all.cmd` 一键停止、桌面快捷方式 | 已实现并验证（DryRun / 停止空跑 / 语法 / ASCII / 快捷方式读回） |
| 2026-10-07 | [Docker 部署设计](<2026-10-07-Docker部署设计.md>) | 设计文档 | docker compose 编排 MySQL + 后端 + Nginx 前端、`db/init` 初始化 SQL 与 Flyway 基线的衔接、流式响应免缓冲、部署与排障说明 | 已实现并端到端验证（真实 MySQL 执行 init SQL + 真实后端通过 Flyway 校验 + API 返回种子数据） |

## 相关文件（不在 docs 下）

| 文件 | 作用 |
| --- | --- |
| [`AGENTS.md`](../AGENTS.md) | 项目协作约定（文档规范等长期要求） |
| [`README.md`](../README.md) | 项目总览与启动说明 |
| [`start-all.cmd`](../start-all.cmd) / [`start-all.ps1`](../start-all.ps1) | 前后端一键启动（双击 `start-all.cmd`） |
| [`stop-all.cmd`](../stop-all.cmd) | 一键停止 8080 / 5173 |
| [`start-backend.cmd`](../start-backend.cmd) / [`start-frontend.cmd`](../start-frontend.cmd) | 单独启动后端 / 前端 |
| [`docker-compose.yml`](../docker-compose.yml) / [`.env.example`](../.env.example) | Docker 一键部署编排与配置模板 |
| [`db/init/01-schema.sql`](../db/init/01-schema.sql) | 容器首次启动时的数据库初始化脚本（含 Flyway 基线） |

## 新增文档流程

1. 文件名用 `YYYY-MM-DD-主题.md`（主题用中文），直接放在 `docs/` 下
2. 正文使用中文，开头写清：日期、状态（如「待实现 / 已实现」）
3. 回到本文件，在「文档清单」表格中补一行
