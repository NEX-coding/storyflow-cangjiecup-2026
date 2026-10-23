# GeekNow Midjourney 视频

视频生产已统一切换到 GeekNow Midjourney，不再调用本地 LTX、H3 或 MiniMax 生成。历史成片继续从原媒体库读取。

- 原生初始时长：5 秒。
- 单图：上传首帧，调用 Video 接口。
- 首尾帧：上传两图，首帧地址 + `--end` 尾帧地址。
- 无参考：提交视频提示词。
- `FAST` 使用 `/mj-fast/mj/submit/video`，`RELAX` 使用 `/mj-relax/mj/submit/video`。仅传 JSON `mode` 未能切换实际计费通道，必须指定路由前缀。
- 已接受的任务保留上游 ID，后台查询原片并归档；刷新页面自动更新生成状态，服务重启后按 ID 继续查询，不重复提交。
- 不传未经 GeekNow 文档确认的 HD 参数。清晰度由上游通道决定，不能保证 FAST 就是 720P。

## 服务器配置

路径：`/opt/storyflow-compute/.env`（权限 600）。

```dotenv
GEEKNOW_BASE_URL=https://api.geeknow.top
GEEKNOW_API_KEY=填写密钥
GEEKNOW_VIDEO_MODE=FAST
GEEKNOW_VIDEO_ENABLED=false
GEEKNOW_PROXY_URL=http://127.0.0.1:8792
VIDEO_PROVIDER=midjourney
```

本次测试服务器直连 GeekNow 被重置，配置了到本机 Clash HTTP 代理的 SSH 反向转发。仅监听服务器回环地址；Mac 登录时由 `com.sfxapp.geeknow-tunnel` 自动维持。该线路依赖 Mac 在线和 Clash 可用。若服务器以后可以直接连接 GeekNow，可清空 `GEEKNOW_PROXY_URL` 并停用此转发。

管理端可以修改运行时密钥；长期密钥写入上述环境文件。密钥不会返回前端或写入源码。

## 2026-10-23 实测结论

密钥认证、参考图上传成功。视频上游两条通道均不可用：

1. FAST：HTTP 400，`mj_fast_video` 尚未由站点管理员配置价格。
2. RELAX：任务提交成功（ID `1792735889988641`），随后 FAILURE，HTTP 403，`视频relax模式暂不开放`。

因此线上 `GEEKNOW_VIDEO_ENABLED=false`，生成请求会直接说明通道未开放，不再提交收费任务，也不会回退旧模型。

需要 GeekNow 管理员开通 FAST 视频模型及计费，或开放 RELAX 视频权限。开通后先设置对应 `GEEKNOW_VIDEO_MODE`，再将 `GEEKNOW_VIDEO_ENABLED=true` 并重启算力后端，实测出片后再确认可用。禁止未检查结果就声称接入已可出片。

文档：https://docs.geeknow.ai/api-reference/images/midjourney/submit
查询：https://docs.geeknow.ai/api-reference/images/midjourney/query
