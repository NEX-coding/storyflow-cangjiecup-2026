# 变更记录

## 2026-10-22

### 移除叮当 / AIC 互联适配
- 删除 `aip-agent/` 与 `docs/aic-智能体互联/`
- 线上部署脚本迁至 `deploy/`（**仅 edu.sfxapp.com** 算力 / 管理端 / nginx）
- 移除平台账号、认领接口与相关前端文案；启动时停用遗留 `aip-platform@storyflow.local` 并清理其会话

## 2026-10-21

### 剧本 / 资产提示词工业化升级
- `script_generate.md` 接入专业影视剧本规范（边界红线、可视化叙事、品类节奏），仍强制输出 StoryFlow JSON
- 资产提取拆为三次专用调用：`asset_extract_character` / `scene` / `prop`（对齐 `docs/` 三份提取词）
- 提取时同时喂入项目原文 + 剧本摘要；`aliases` 写入 description；每条资产再生成 `imagePrompt`
- `asset_extract.md` 保留为重写/兜底；重启后端后 `PromptCatalog.syncFromClasspath` 生效

## 2026-10-07

### 模型端点 Failover
- `OllamaClient` 支持多端点：主 URL 失败时自动切换 `fallback-urls`，成功后 sticky 到可用端点
- OpenAI 兼容模式（URL 以 `/v1` 结尾）与 Ollama 原生 API 自动识别
- edu 模型入口：`https://edu.sfxapp.com/model-api-mac/v1`（`qwen3.5:4b`），备用同域 `model-api`（`qwen3.5:0.8b`）
- Admin `/api/admin/ai-models/health` 返回 `activeBaseUrl` 与各端点 `endpoints` 状态

### 访客限制
- 访客配额：按浏览器 `storyflow-anonymous-id` Cookie 隔离；最多 3 项目；每日剧本/资产/分镜各 1 次；助手 5 次
- Admin API 需 ADMIN 角色；前端 AI 设置页对访客只读

## 2026-10-05

### 线上部署（仅 edu）
- 正式入口：https://edu.sfxapp.com/storyflow/compute/web/（后端 :9121）
- 部署说明见 `deploy/README.md`；不再部署其它服务器

## 2026-10-04

### 文档
- 新增 `docs/README.md` 文档索引
- 新增开发指南、架构说明、认证与注册、API 概览、AI 助手能力说明
- 新增 Cursor 规则：外部要求须文档化（`.cursor/rules/document-external-requirements.mdc`）

### 认证
- 邮箱验证码注册（163 SMTP，与课表站同配置方式）
- 移除 `local@storyflow.com` 默认账号；未登录返回 401
- 登录弹窗：侧栏「点击登录/账户」样式

### AI 助手
- 分镜重生支持语言意图：「我要中文」→ 中文 videoPrompt / 参考图 prompt
- 识别「重新给我生成」等为重生意图
- LLM 分类器增加 `promptLanguage` 字段

### 备份
- 项目 tar.gz 打包脚本说明（见开发指南）

## 2026-09 ~ 2026-10（MVP 阶段）

- Spring Boot + React 工作台：原文 → 剧本 → 资产 → 分镜
- Ollama `qwen3.5:4b` 集成
- AI 助手流式对话与多意图（删分镜、重生、写原文等）
- 版本快照、导出 JSON/Markdown
- 管理端 Prompt 模板与 Ollama 配置
