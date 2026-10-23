# StoryFlow Prompt Catalog

提示词与系统技能的**唯一源文件**（StoryFlow 提示词模板）。

| 目录 | DB category | 用途 |
|------|-------------|------|
| `system/*.md` | 文件名 | LLM system：剧本 / 资产 / 分镜 / 助手 |
| `system/script_generate.md` | `script_generate` | 小说→剧本（工业化编剧规范 + JSON 契约） |
| `system/asset_extract_character.md` | `asset_extract_character` | 角色提取（专用） |
| `system/asset_extract_scene.md` | `asset_extract_scene` | 场景提取（专用） |
| `system/asset_extract_prop.md` | `asset_extract_prop` | 道具提取（专用） |
| `system/asset_extract.md` | `asset_extract` | 资产重写/兜底（含 imagePrompt） |
| `style/*.md` | `style.{名}` | 作品画风总览（媒介+光影+排除） |
| `style/{名}/meta.json` | `style.{名}.meta` | 媒介族 medium + 设定板 sheetLayout |
| `style/{名}/character.md` | `style.{名}.character` | 角色生图前置 |
| `style/{名}/scene.md` | `style.{名}.scene` | 场景前置 |
| `style/{名}/prop.md` | `style.{名}.prop` | 道具前置 |
| `style/{名}/video.md` | `style.{名}.video` | 分镜/视频继承前置 |
| `style/{名}/negative.md` | `style.{名}.negative` | 排除项 |
| `style/{名}/sheet.md` | `style.{名}.sheet` | 角色设定板骨架（`{name}` `{look}` `{style}`） |
| `skill/*.md` | `skill.{id}` | 可复用技能块（运镜、设定板规则等） |

**风格包原则**：构图/画风/媒介分文件；Java 只编排。`medium=photo` 禁止线稿；`sheetLayout=photo_bust_sheet` 用真人躯干板。

加载优先级：`prompt_templates` 表（active 最新版）→ 本目录 classpath → 代码内极简兜底。

启动时 `PromptCatalog.syncFromClasspath()` 会把本目录同步进 DB，改 md 后重新部署即可生效；管理端也可临时改 DB。
