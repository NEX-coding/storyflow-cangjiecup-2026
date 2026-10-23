# StoryFlow

AI 辅助剧本与提示词生产平台。将小说原文转为结构化剧本，提取资产（人物/场景/道具），划分分镜模块，生成视频提示词与参考图提示词（首帧/尾帧/其他）。支持本机 Ollama 生成参考图，并通过 GeekNow Midjourney 生成视频（视频需上游通道权限）。

**邮箱注册登录**，数据按用户隔离。用户端与管理端为两个独立前端应用。

📚 **完整文档见 [docs/README.md](./docs/README.md)**

## 技术栈

- 后端：Spring Boot 3 + JPA + H2/MySQL + Spring Mail
- 用户端：React 18 + TypeScript + Vite + Ant Design（端口 5173）
- 管理端：React 18 + TypeScript + Vite + Ant Design（端口 5174）
- AI：Ollama 本地部署 `qwen3.5:4b`

## 快速开始

### 1. 启动 Ollama 并拉取模型

```bash
ollama pull qwen3.5:4b
ollama serve
```

### 2. 配置邮件（注册验证码，可选但推荐）

```bash
cp backend/.env.example backend/.env
# 编辑 SMTP_USER / SMTP_PASS（163 授权码）
# 或使用 backend/src/main/resources/application-local.yml（已 gitignore）
```

### 3. 启动后端

需要 **Java 21**：

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home
cd backend
mvn spring-boot:run
```

后端默认运行在 http://localhost:8080

### 4. 启动用户端

```bash
cd frontend
pnpm install
pnpm dev
```

访问 http://localhost:5173 → 侧栏「点击登录/账户」注册或登录

### 5. 启动管理端

```bash
cd admin
pnpm install
pnpm dev
```

访问 http://localhost:5174

## 核心流程（用户端）

1. 注册/登录
2. 创建项目
3. 在「原文」Tab 粘贴小说内容并保存
4. 点击「一键生成」或分步：生成剧本 → 提取资产 → 划分分镜
5. 在分镜模块中编辑视频/参考图提示词，设置首帧/尾帧/其他
6. 使用右侧 AI 助手随时对话修改（可说「重新生成第 1 个分镜，我要中文」）
7. 导出 JSON / Markdown 提示词包

## 项目结构

```
StoryFlow/
├── backend/                 # Spring Boot API
│   ├── assistant/           # AI 助手（意图 + SSE）
│   ├── ai/                  # LlmGateway（Ollama）
│   └── service/             # 工作台流水线
├── frontend/                # 用户端
│   └── src/modules/assistant/
├── admin/                   # 管理端
├── deploy/                  # 线上部署脚本与 nginx 片段
├── docs/                    # 文档（开发指南、API 等）
└── .cursor/rules/           # Agent 规则
```

## API 概览

详见 [docs/API概览.md](./docs/API概览.md)

- `POST /api/auth/register/send-code` — 发送注册验证码
- `POST /api/auth/register` — 注册
- `POST /api/auth/login` — 登录
- `GET/POST /api/projects` — 项目管理
- `POST /api/projects/{id}/script/generate` — AI 生成剧本
- `POST /api/projects/{id}/assets/extract` — AI 提取资产
- `POST /api/projects/{id}/shots/generate` — AI 划分分镜
- `POST /api/projects/{id}/assistant/chat/stream` — AI 助手（SSE）
- `/api/admin/*` — 管理端（Prompt 模板、Ollama 配置）

## 部署

**仅部署到 edu.sfxapp.com**（算力入口），不部署其它服务器。

- 演示：https://edu.sfxapp.com/storyflow/compute/web/
- 脚本与目录说明见 [`deploy/README.md`](./deploy/README.md)

## 视频生成

新视频统一使用 GeekNow Midjourney，初始时长 5 秒，旧本地视频模型已停用。配置与当前上游限制见 [GeekNow 视频接入](docs/GeekNow视频接入.md)。
