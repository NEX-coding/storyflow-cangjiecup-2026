# API 概览

> 最后更新：2026-10-04 
> Base URL：`http://localhost:8080/api` 
> 除 `/auth/register*`、`/auth/login` 外，业务接口需登录 Token。

响应格式：`{ "code": 200, "message": "success", "data": ... }` 
错误：`code` 非 200，`message` 为可读说明。

---

## 认证 `/auth`

| Method | Path | 说明 |
|---|---|---|
| POST | `/auth/register/send-code` | 发送注册验证码 |
| POST | `/auth/register` | 邮箱验证码注册 |
| POST | `/auth/login` | 登录 |
| POST | `/auth/logout` | 登出 |
| GET | `/auth/me` | 当前用户 |

---

## 项目 `/projects`

| Method | Path | 说明 |
|---|---|---|
| GET | `/projects` | 当前用户的项目列表 |
| POST | `/projects` | 创建项目 |
| POST | `/projects/demo` | 创建示例项目 |
| GET | `/projects/{id}` | 项目详情 |
| PUT | `/projects/{id}` | 更新（标题、风格等） |
| DELETE | `/projects/{id}` | 删除项目 |

---

## 工作台 `/projects/{id}/...`

### 原文与剧本

| Method | Path | 说明 |
|---|---|---|
| GET/PUT | `/source` | 原文读写 |
| POST | `/script/generate` | AI 生成剧本 |
| GET | `/script` | 剧本列表 |
| PUT | `/script/{scriptId}` | 更新单条剧本 |

### 资产

| Method | Path | 说明 |
|---|---|---|
| POST | `/assets/extract` | AI 提取资产 |
| GET | `/assets` | 资产列表 |
| POST | `/assets` | 手动新增 |
| PUT | `/assets/{assetId}` | 更新 |
| DELETE | `/assets/{assetId}` | 删除 |
| POST | `/assets/{assetId}/regenerate-prompt` | 重生生图提示词 |

### 分镜

| Method | Path | 说明 |
|---|---|---|
| POST | `/shots/generate` | AI 划分并生成全部分镜提示词 |
| GET | `/shots` | 分镜列表（含 referencePrompts） |
| PUT | `/shots/{shotId}` | 更新分镜 |
| DELETE | `/shots/{shotId}` | 删除分镜 |
| PUT | `/shots/reorder` | 排序 |
| POST | `/shots/insert` | 插入空分镜 |
| POST | `/shots/merge` | 合并分镜 |
| POST | `/shots/{shotId}/split` | 拆分分镜 |
| POST | `/shots/{shotId}/regenerate-prompts` | 重生该分镜提示词 |
| POST | `/shots/regenerate-batch` | 批量重生准备（快照） |
| PUT | `/shots/{shotId}/refs/{refId}` | 更新参考图提示词/类型 |

### 快照与导出

| Method | Path | 说明 |
|---|---|---|
| GET | `/snapshots` | 版本快照列表 |
| POST | `/snapshots` | 手动快照 |
| POST | `/snapshots/{snapshotId}/restore` | 回滚 |
| POST | `/export` | 导出提示词包 |

---

## AI 助手 `/projects/{id}/assistant`

| Method | Path | 说明 |
|---|---|---|
| POST | `/assistant/chat` | 非流式对话 |
| POST | `/assistant/chat/stream` | **SSE 流式**（推荐） |
| GET | `/assistant/history` | 聊天历史 |

SSE 事件：`status`、`token`、`shots`、`done` 等。

---

## 其他

| Method | Path | 说明 |
|---|---|---|
| GET | `/projects/{id}/ai-history` | AI 生成历史 |
| GET/POST | `/feedback` | 用户反馈 |

---

## 管理端 `/admin`

| Method | Path | 说明 |
|---|---|---|
| POST | `/admin/auth/login` | 管理端独立账号登录 |
| POST | `/admin/auth/logout` | 退出 |
| GET | `/admin/auth/me` | 当前管理员 |
| GET | `/admin/dashboard` | 仪表盘 |
| GET | `/admin/users` | 用户列表（搜索/分页） |
| GET | `/admin/users/{id}` | 用户详情 |
| PUT | `/admin/users/{id}/status` | 启用/停用用户 |
| GET | `/admin/feedbacks` | 用户反馈列表 |
| PUT | `/admin/feedbacks/{id}` | 处理反馈（状态/回复） |
| GET/POST/PUT | `/admin/prompt-templates` | Prompt 模板 |
| GET/PUT | `/admin/ai-models` | Ollama 配置 |
| GET | `/admin/ai-models/health` | 模型连通性 |
