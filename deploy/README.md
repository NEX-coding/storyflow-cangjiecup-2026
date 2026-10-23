# StoryFlow 部署说明（仅 edu）

线上只部署到 **edu.sfxapp.com**，不再维护其它服务器（含 GPU 机、平台旧实例等）。

## 入口

| 用途 | 地址 |
|---|---|
| 用户端（校赛 / 评审演示） | https://edu.sfxapp.com/storyflow/compute/web/ |
| 管理端 | https://edu.sfxapp.com/storyflow/admin/ |
| 算力 API | `/storyflow/api/compute/` → 本机 `127.0.0.1:9121` |

## 一键部署（本机执行）

```bash
# 算力版前端 + 后端 :9121
./deploy/deploy-compute-sfxapp.sh

# 管理端静态资源（可选）
./deploy/deploy-admin-sfxapp.sh
```

默认 SSH：`root@146.56.235.224`（可用环境变量 `STORYFLOW_DEPLOY_HOST` 覆盖）。

## 服务器目录（edu）

| 路径 | 说明 |
|---|---|
| `/opt/storyflow-compute/` | 算力后端 jar、启动脚本、日志 |
| `/var/www/storyflow/compute/web/` | 用户端静态资源 |
| `/var/www/storyflow/admin/` | 管理端静态资源 |
| `/opt/storyflow-media/` | 媒体落盘 |
| nginx snippets | `nginx-storyflow-locations.conf` / `nginx-storyflow-upstream.conf` |

后端启动脚本：`start-compute-backend.sh`（profile `compute`，端口 **9121**）。

## 说明

- 评审与日常演示只认上述 edu 算力入口。
- 模型 / 媒体服务若未就绪，生图、生视频可能不可用；文本流水线仍可按 edu 上配置的模型端点工作。
