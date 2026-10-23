【画风一致性原则｜StoryFlow 风格包】

1) 风格包四层（见 prompts/style/{名}/）
- 构图 sheet.md：设定板骨架（anime / photo_bust / cg），只用于角色 imagePrompt
- 画风 style.md + 短名：全片统一
- 媒介 meta.medium：lineart|paint|photo|cg|ink|cyber|film；写实禁止线稿
- 品质：无文字水印等通用收尾，不含媒介

2) 前置分文件：character / scene / prop / video.md；negative.md 为排除项

3) 角色 → 分镜
- 从角色 imagePrompt 抽共用画风词（排除设定板构图词）
- 分镜须含画风词；缺则追加「，统一画风：…」
- video.md 前置：仅 videoPrompt / 视频 GPU；首尾帧静帧须去掉运镜/时长词后再用画质段
- 单图·故事版：遵循 skill/storyboard_production；三视图仅顶部参考条；底部必须是叙事制作格，不得整板设定表
- 首尾帧静帧：遵循 skill/first_last_frame_production；单帧剧照 + 身份锁定；禁止设定板排版
- 故事版必须含板面文字：角标、场景名、台词「角色名：内容」；禁止「无文字」压过标注；仅禁平台水印/logo
- videoPrompt（单图）：
  · 风格只保留开头一行【风格参考】（来自 video.md / 用户参考），禁止时间轴末尾再堆「统一画风/真人写实长串」
  · 按秒引用底部分镜格；顶部参考条不作视频主画面
  · 每秒段 1 主动作 + 1 运镜；成片无烧录字幕
- videoPrompt（首尾帧）：只写两帧之间过渡；勿复述静帧内容

4) LLM 写作
- 故事版：导演执行规约（空间·镜头·动作·焦点·标注），预演可读性优先
- 首尾帧静帧 80–160 字（冻结瞬间·同人同服同场景）；人物资产约 110–200 字；单图故事版可更长（项目卡+连续性+逐格五行）
- 末尾画风短名补全主要用于资产/静帧；单图 videoPrompt 不要在末尾重复贴画风小作文
