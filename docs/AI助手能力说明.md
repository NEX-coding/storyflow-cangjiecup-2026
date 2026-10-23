# AI 助手能力说明

> 最后更新：2026-10-23

## 0. 助手大脑（StoryFlow 助手）

默认 `storyflow.assistant.provider=hybrid`：

| 路径 | 用途 |
|---|---|
| **StoryFlow 工具链** | 写原文 / 生成剧本 / 提取资产 / 分镜提示词等**写操作**（意图路由 + `WorkspaceService`） |
| **增强推理（cursor-agent ask）** | 普通问答、创作建议、解释类闲聊 |
| **Ollama / 个人模型** | 增强推理不可用时的闲聊回退；流水线生成仍走文本模型 |

配置项（`application.yml` / 环境变量）：

- `storyflow.assistant.provider`：`hybrid` | `codex` | `ollama`
- `STORYFLOW_CODEX_BIN` / `codex-bin`：cursor-agent 路径（可空，自动探测）
- `CURSOR_API_KEY` 或本机执行 `cursor-agent login`

状态接口 `GET /api/ai-models/status` 的 `assistant` 字段会显示增强推理是否可用。

## 1. 入口

工作台右侧 **StoryFlow 助手** 面板，通过 SSE 流式接口：

```
POST /api/projects/{id}/assistant/chat/stream
```

请求体可含：
- `message`：用户自然语言
- `focus`：`global` | `shot` | `asset` …
- `focusId`：聚焦的分镜/资产 ID
- `selectedShotIds`：勾选的分镜 ID 列表

## 2. 意图识别机制

1. **关键词兜底**（快、确定性强）
2. **LLM 轻量分类器**（`classifyShotsIntent` 等）
3. 分镜类：**关键词优先于 LLM**（如语言偏好）

未指定分镜编号时，不会默认「全部生成」，会提示用户说明编号或勾选分镜。

## 3. 支持的主要意图

### 3.1 流水线

| 用户说法示例 | 行为 |
|---|---|
| 根据原文生成剧本 | 生成结构化剧本 |
| 从剧本提取资产 | 提取人物/场景/道具 |
| 生成分镜提示词 / 重新生成第 N 个 | 见 3.2 |

### 3.2 分镜提示词

| 用户说法示例 | 行为 |
|---|---|
| 生成第 1 个分镜提示词 | 生成指定分镜 |
| 重新生成第 1 个分镜提示词 | 重生指定分镜 |
| 第一个分镜的提示词重新给我生成，**我要中文** | 重生 #1，**中文** videoPrompt / 参考图 prompt |
| 删除第 2、3 个分镜 | 删除指定分镜 |
| 删除这些分镜（需先勾选） | 删除选中分镜 |

**语言偏好**（关键词识别，优先于 LLM）：
- 中文：`中文`、`我要中文`、`用中文`、`chinese` …
- 英文：`英文`、`english` …
- 未提及：默认 **中文** 提示词；说「用英文」可切回英文

### 3.3 剧本 / 资产 / 原文

| 类型 | 示例 |
|---|---|
| 重写第 N 条剧本 | 单条剧本 AI 改写 |
| 删除第 N 章 | 删原文章节（非分镜） |
| 重新生成某某的生图提示词 | 单资产 imagePrompt |
| 新增角色 XXX | 创建资产 |
| 写/填充原文 | 助手写小说章节（与剧本生成区分） |

## 4. 分镜提示词结构

每个分镜包含：
- **videoPrompt**：视频提示词（中/英由语言偏好决定）
- **referencePrompts[]**：
 - `type`: `first_frame` | `last_frame` | `other`
 - `prompt`: 画面提示词（与 videoPrompt 同语言）
 - `note`: 中文说明

生成路径：`AssistantService` → `WorkspaceService.regenerateShotPrompts` → `LlmGateway.streamGenerateShot`

## 5. 流式反馈

重生分镜时 SSE 会推送：
- `status`：如「正在生成分镜 #1 的中文提示词…」
- `shotStream`：videoPrompt partial 文本
- `shots`：`updated` + 分镜编号
- `done`：`shotsUpdated: true` + 助手回复文案

## 6. 相关代码

| 文件 | 说明 |
|---|---|
| `assistant/AssistantService.java` | 意图解析与执行 |
| `assistant/AssistantService.java` → `extractPromptLanguage` | 语言偏好 |
| `ai/CodexClient.java` | 本机 Codex（cursor-agent ask） |
| `ai/LlmGateway.java` → `streamAssistantReply` | 闲聊：Codex 优先，回退 Ollama |
| `ai/LlmGateway.java` → `streamGenerateShot` | 按语言构造 Prompt |
| `frontend/modules/assistant/` | 助手 UI 与 SSE 消费 |
