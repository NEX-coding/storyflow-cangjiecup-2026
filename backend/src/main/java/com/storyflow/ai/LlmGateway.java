package com.storyflow.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyflow.common.BusinessException;
import com.storyflow.config.StoryflowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    /** 模型常输出 "type": first_frame（未加引号），Jackson 会报 Unrecognized token */
    private static final Pattern UNQUOTED_REF_TYPE = Pattern.compile(
            "(:\\s*)(first_frame|last_frame|other)(\\s*[,}\\]])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UNQUOTED_TYPE_KEY = Pattern.compile(
            "([{\\[,]\\s*)(type)(\\s*:)",
            Pattern.CASE_INSENSITIVE);

    private final OllamaClient ollamaClient;
    private final CodexClient codexClient;
    private final PromptCatalog promptCatalog;
    private final PromptPipeline promptPipeline;
    private final com.storyflow.ai.llm.ScriptLlmService scriptLlm;
    private final ObjectMapper objectMapper;
    private final StoryflowProperties properties;

    public LlmGateway(OllamaClient ollamaClient, CodexClient codexClient, PromptCatalog promptCatalog,
                      PromptPipeline promptPipeline,
                      com.storyflow.ai.llm.ScriptLlmService scriptLlm,
                      ObjectMapper objectMapper, StoryflowProperties properties) {
        this.ollamaClient = ollamaClient;
        this.codexClient = codexClient;
        this.promptCatalog = promptCatalog;
        this.promptPipeline = promptPipeline;
        this.scriptLlm = scriptLlm;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<ScriptDto> generateScript(String sourceText, String style) {
        return scriptLlm.generateScript(sourceText, style);
    }

    public List<AssetDto> extractAssets(String scriptJson, String style) {
        return extractAssets(null, scriptJson, style);
    }

    /** 角色/场景/道具分三次提取，再生成 imagePrompt */
    public List<AssetDto> extractAssets(String novelText, String scriptJson, String style) {
        String styleName = style == null || style.isBlank() ? "日漫赛璐璐" : style.trim();
        String novel = novelText == null || novelText.isBlank()
                ? "（无单独原文，以下剧本摘要即为故事来源）"
                : novelText.trim();
        String plot = scriptJson == null ? "" : scriptJson;
        List<AssetDto> all = new ArrayList<>();
        all.addAll(extractAssetsOfType("character", novel, plot, styleName));
        all.addAll(extractAssetsOfType("scene", novel, plot, styleName));
        all.addAll(extractAssetsOfType("prop", novel, plot, styleName));
        return dedupeAssets(all);
    }

    /** 单类型提取（供流式落库：人物→场景→道具逐步推送） */
    public List<AssetDto> extractAssetsOfType(String type, String novelText, String scriptJson, String style) {
        String styleName = style == null || style.isBlank() ? "日漫赛璐璐" : style.trim();
        String novel = novelText == null || novelText.isBlank()
                ? "（无单独原文，以下剧本摘要即为故事来源）"
                : novelText.trim();
        String plot = scriptJson == null ? "" : scriptJson;
        String t = type == null ? "" : type.trim().toLowerCase();
        String template = switch (t) {
            case "character" -> "asset_extract_character";
            case "scene" -> "asset_extract_scene";
            case "prop" -> "asset_extract_prop";
            default -> throw new IllegalArgumentException("未知资产类型: " + type);
        };
        return extractAssetsOfType(t, template, novel, plot, styleName);
    }

    private List<AssetDto> extractAssetsOfType(String type, String templateCategory,
                                               String novelText, String scriptJson, String styleName) {
        String system = getTemplate(templateCategory);
        String propGate = "prop".equals(type)
                ? """
                
                【本次道具提取额外强调】
                - 只提真正需要单独建资产的关键道具；宁缺毋滥，宁可 [] 也不要堆杂物。
                - 短剧优先 0–5 件，最多 8 件；拿不准是否关键 → 不提。
                - 排除：日常餐具消耗品、服装穿戴、桌椅床门等场景陈设、一笔带过的背景物。
                """
                : "";
        String user = """
                风格：%s
                
                提供的故事情节为：
                %s
                
                提供的剧本摘要为：
                %s
                %s
                根据上面的信息按系统规则提取，只输出 JSON 数组。若确实无可提取项，输出 []。
                """.formatted(styleName, novelText, scriptJson, propGate);
        JsonNode node = callJsonAllowEmpty(system, user);
        List<AssetDto> result = new ArrayList<>();
        for (JsonNode item : itemsNode(node)) {
            String name = text(item, "name", "title");
            if (name == null || name.isBlank()) {
                continue;
            }
            String aliases = text(item, "aliases", "alias");
            String description = text(item, "description", "desc", "介绍");
            if (aliases != null && !aliases.isBlank()) {
                description = description == null || description.isBlank()
                        ? "代称：" + aliases
                        : description.trim() + "\n代称：" + aliases.trim();
            }
            AssetDto draft = new AssetDto(type, name.trim(), description == null ? "" : description.trim(), "");
            String imagePrompt;
            try {
                imagePrompt = regenerateAssetPrompt(draft, styleName);
            } catch (Exception ex) {
                imagePrompt = "character".equals(type)
                        ? fallbackCharacterSheetPrompt(draft.name(), draft.description(), styleName)
                        : promptPipeline.normalizeAssetPersist(
                        ensureStyleKeyword(
                                ("prop".equals(type)
                                        ? "道具多角度参考表，正面侧面俯视同页，白底，禁止人物，"
                                        : "空镜环境设定主视觉，禁止任何人物路人剪影，")
                                        + draft.name() + "，" + draft.description(),
                                styleName),
                        type, styleName);
            }
            result.add(new AssetDto(type, draft.name(), draft.description(), imagePrompt));
        }
        // 道具宁缺毋滥：模型偶发滥提时硬截断（顺序上通常已按重要性排列）
        if ("prop".equals(type) && result.size() > MAX_EXTRACTED_PROPS) {
            return new ArrayList<>(result.subList(0, MAX_EXTRACTED_PROPS));
        }
        return result;
    }

    /** 单次道具提取硬上限，与 asset_extract_prop 提示词一致 */
    private static final int MAX_EXTRACTED_PROPS = 8;

    private static List<AssetDto> dedupeAssets(List<AssetDto> items) {
        List<AssetDto> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AssetDto item : items) {
            if (item == null || item.name() == null || item.name().isBlank()) {
                continue;
            }
            String key = (item.type() == null ? "" : item.type().trim().toLowerCase())
                    + "|" + item.name().trim().toLowerCase();
            if (seen.add(key)) {
                result.add(item);
            }
        }
        return result;
    }

    /** 资产生图规则：见 {@code prompts/skill/asset_image_guide.md} */
    String assetImagePromptGuide() {
        return assetImagePromptGuide(null);
    }

    String assetImagePromptGuide(String stylePreset) {
        String styleName = stylePreset == null || stylePreset.isBlank() ? "当前选定画风" : stylePreset.trim();
        StylePack pack = promptCatalog.stylePack(styleName);
        String mediumHint = pack.allowsLineArt()
                ? "本画风媒介=lineart，可用「干净线稿」；勿写写真/毛孔。"
                : "本画风媒介=" + pack.medium() + "，禁止写「干净线稿/干净线条」；媒介只跟风格包。";
        String sheetHint = switch (pack.sheetLayout()) {
            case StylePack.LAYOUT_PHOTO -> "人物设定板用 photo_bust_sheet（左躯干三格+右正脸特写）。";
            case StylePack.LAYOUT_CG -> "人物设定板用 cg_turnaround（正背全身+面部特写）。";
            default -> "人物设定板用 anime_turnaround（左头肩特写+右正侧背全身）。";
        };
        return promptCatalog.assetImageGuide()
                + "\n" + promptCatalog.styleConsistencyGuide()
                + "\n运行时画风名：「" + pack.id() + "」；末尾必须带该画风短句，禁止夹带其他画风名。"
                + "\n" + mediumHint + " " + sheetHint
                + "\n角色前置参考：「" + pack.characterPrefix() + "」"
                + "\n人物约 110-200 字；道具用多角度参考表；场景用环境设定主视觉（可带小 inset）。";
    }

    /** 作品画风句：见 {@code prompts/style/*.md} */
    String chineseStylePrompt(String style) {
        return promptCatalog.style(style);
    }

    /** 运镜词表：见 {@code prompts/skill/camera_lexicon.md} */
    String cameraLanguageSkill() {
        return promptCatalog.cameraLexicon();
    }

    private static String assetQualitySuffix(String type) {
        return PromptPrinciples.assetQualitySuffix(type);
    }

    private static boolean looksLikeCharacterSheetPrompt(String prompt) {
        return PromptPrinciples.looksLikeCharacterSheetPrompt(prompt);
    }

    /** 模型只吐画质词时的兜底：左脸特写 + 正侧背全身（漫剧多参考设定板） */
    String fallbackCharacterSheetPrompt(String name, String description, String style) {
        return promptCatalog.fallbackCharacterSheet(name, description, style);
    }

    public List<ShotDto> generateShots(String scriptJson, String assetsJson, String style) {
        return generateShots(scriptJson, assetsJson, style, PromptLanguage.ZH, ProjectSettingsSupport.MODE_FIRST_LAST);
    }

    public List<ShotDto> generateShots(String scriptJson, String assetsJson, String style,
                                       PromptLanguage language) {
        return generateShots(scriptJson, assetsJson, style, language, ProjectSettingsSupport.MODE_FIRST_LAST);
    }

    public List<ShotDto> generateShots(String scriptJson, String assetsJson, String style,
                                       PromptLanguage language, String refPromptMode) {
        PromptLanguage lang = language != null ? language : PromptLanguage.ZH;
        String mode = ProjectSettingsSupport.normalizeMode(refPromptMode);
        String system = shotGenerateSystemPrompt(lang, mode);
        String promptLang = lang.isChinese() ? "中文" : "英文";
        String styleBlock = styleDirectiveForVideoModes(style, mode);
        String refSkill = batchReferenceSkill(mode, promptLang);
        String videoHint = ProjectSettingsSupport.MODE_NONE.equals(mode)
                ? "videoPrompt: 无参考纯文生视频——【风格参考】一行 +【一句话概述】+【动作】先…随后…最后… +【镜头】+【画面】+「时长：X秒」(X=6或10)；禁止【画面风格】长段/故事版/首尾帧模板"
                : ProjectSettingsSupport.MODE_SINGLE.equals(mode)
                ? "videoPrompt: 单图模式见下方 storyboard 规约（风格参考一行 + 故事版绑定 + 时间轴）；禁止【画面风格】长段"
                : "videoPrompt: 首尾帧过渡——【风格参考】一行 +【首尾帧驱动】+【一句话概述】+【过渡】先…随后…最后… +【镜头】+「时长：X秒」(X=6或10)；禁止复述静帧清单、禁止【画面风格】长段";
        String user = """
                %s
                根据剧本划分分镜。严格输出 JSON：{"items":[...]}
                每条用结构化%s提示词（对齐字字动画图/视分工，不是英文关键词堆砌）：
                - scriptIndex: 整数，对应剧本序号从0开始
                - %s
                %s
                - 相邻分镜须时空/人物/道具连贯；仅映射资产库已有角色场景道具，可用 (@资产名)
                - type 必须带双引号："first_frame"|"last_frame"|"other"
                
                剧本：
                %s
                
                资产（人物含 description/imagePrompt 外形；单图模式须把外形写进故事版的人物锁定，禁止做成设定表拼贴）：
                %s
                """.formatted(styleBlock, promptLang, videoHint, refSkill, scriptJson, assetsJson);
        JsonNode node = callJsonWithRetry(system, user);
        return finalizeShots(parseShots(node), style, assetsJson, mode);
    }

    /** 流式生成单个分镜提示词（轻量请求，避免整批划分） */
    public ShotDto streamGenerateShot(String scriptJson, String assetsJson, String style,
                                      java.util.function.Consumer<String> onVideoPromptPartial) {
        return streamGenerateShot(scriptJson, assetsJson, style, PromptLanguage.ZH,
                ProjectSettingsSupport.MODE_FIRST_LAST, onVideoPromptPartial);
    }

    public ShotDto streamGenerateShot(String scriptJson, String assetsJson, String style,
                                      PromptLanguage language,
                                      java.util.function.Consumer<String> onVideoPromptPartial) {
        return streamGenerateShot(scriptJson, assetsJson, style, language,
                ProjectSettingsSupport.MODE_FIRST_LAST, onVideoPromptPartial);
    }

    public ShotDto streamGenerateShot(String scriptJson, String assetsJson, String style,
                                      PromptLanguage language, String refPromptMode,
                                      java.util.function.Consumer<String> onVideoPromptPartial) {
        PromptLanguage lang = language != null ? language : PromptLanguage.ZH;
        String mode = ProjectSettingsSupport.normalizeMode(refPromptMode);
        String system = shotGenerateSystemPrompt(lang, mode);
        String promptLang = lang.isChinese() ? "中文" : "英文";
        String lengthHint = ProjectSettingsSupport.MODE_SINGLE.equals(mode)
                ? (lang.isChinese() ? "180-320 字" : "120-220 词")
                : (lang.isChinese() ? "80-140 字" : "60-100 词");
        // 三种模式：静帧/故事版可用【画面风格】；videoPrompt 一律只用【风格参考】一行
        String styleBlock = styleDirectiveForVideoModes(style, mode);
        String refSkill = singleShotReferenceSkill(mode, promptLang, lang);
        String videoSkill;
        if (ProjectSettingsSupport.MODE_SINGLE.equals(mode)) {
            videoSkill = """
                - videoPrompt（%s，建议 %s）：必须服从本镜故事版格序（图生视频，勿另起情节）
                  【风格参考】仅一行画风短句（全篇只出现一次；禁止时间轴末尾再贴统一画风小作文）
                  【故事版绑定】以故事版底部叙事格为参考；只推进分镜01–05；顶部参考条只锁人设/环境
                  【一句话概述】与故事版微简述一致（≤40字）
                  【故事版时间轴】时长：X秒（X=6或10）。每段只写 1 主动作 + 1 运镜，禁止「先…随后…最后…」塞进同一秒段：
                    0.0–1.2s → 分镜01｜景别｜运镜｜一个冻结动作｜可选环境声/台词
                    1.2–2.4s → 分镜02｜…（共5段连续无空档）
                  【声音】dialogue 非空则嵌入对应秒段「角色名：台词」原文；成片无烧录字幕
                  【约束】不改身份服装场景；勿写无文字/8K/超写实长串
                - 重要：剧本 dialogue 非空时，故事版板面文字与视频时间轴台词都必须原样抄写
                """.formatted(promptLang, lengthHint);
        } else if (ProjectSettingsSupport.MODE_NONE.equals(mode)) {
            videoSkill = """
                - videoPrompt（%s，建议 %s）：无参考 · 纯文生视频，禁止套用故事版/首尾帧模板
                  【风格参考】一行画风短句（全篇只出现一次；禁止再写【画面风格】长段）
                  【一句话概述】≤40字
                  【动作】先…随后…最后…（忠实剧本的一条连续动作链）
                  【镜头】景别 + 唯一运镜（运镜词表只选 1 种）
                  【画面】时间；地点；人物（可用 (@资产名)）
                  【节奏】时长：X秒（X=6或10）；台词时段固定不推拉；成片无烧录字幕
                  【一致】角色外形与资产一致；不换脸；不加戏外人物
                """.formatted(promptLang, lengthHint);
        } else {
            videoSkill = """
                - videoPrompt（%s，建议 %s）：首尾帧驱动，只写两帧之间过渡（开源 FLF）
                  【风格参考】一行画风短句（可含运镜气质；全篇只出现一次）
                  【首尾帧驱动】以 first_frame 起幅为起点、last_frame 落幅为终点；只生成中间连续动作
                  【一句话概述】≤40字（勿复述两帧静帧文案）
                  【过渡】从起幅冻结点到落幅冻结点的一条物理路径；必须「先…随后…最后…」写清动作链
                  【镜头】景别 + 唯一运镜（运镜词表只选 1 种；台词时段固定）
                  【声音】剧本 dialogue 非空时必须原样抄写「角色名：台词」（多人用 / 分隔；画外音保留标注）；成片无烧录字幕
                  【一致】同一人设服装场景；不换脸；成片无烧录字幕
                  【节奏】时长：X秒（X=6或10）
                  严禁：删改/省略剧本台词；【画面风格】长段；粘贴资产【角色姓名】【脸部特征】【服装设定】等字段；
                  正脸特写/无环境无道具/设定板构图；末尾「统一画风」小作文；复述静帧外形清单
                """.formatted(promptLang, lengthHint);
        }
        String user = """
                %s
                只为下面这一条剧本生成 1 个分镜。严格输出完整合法 JSON（必须闭合所有括号），格式：{"items":[{...}]}
                items 只能有 1 条。写法对齐字字动画「图片提示词 / 视频提示词」分工（%s，不是英文关键词堆砌）：
                - scriptIndex: 0
                %s
                %s
                %s
                不要输出 markdown 代码块。
                
                剧本：
                %s
                
                相关资产（人物请读 description/imagePrompt 外形特征；画风以本条为准；未出现资产禁止写入）：
                %s
                """.formatted(styleBlock, promptLang, videoSkill.stripTrailing(), refSkill,
                cameraLanguageSkill(), scriptJson, assetsJson);

        StringBuilder buffer = new StringBuilder();
        String[] lastPartial = {null};
        try {
            // 中文双参考图提示词较长，1536 易截断导致 Array 未闭合
            String raw = ollamaClient.streamChatText(system, user, 4096, token -> {
                buffer.append(token);
                String partial = extractPartialVideoPrompt(buffer.toString());
                if (partial != null && !partial.equals(lastPartial[0]) && onVideoPromptPartial != null) {
                    lastPartial[0] = partial;
                    onVideoPromptPartial.accept(partial);
                }
            });
            JsonNode node = parseModelJson(raw);
            List<ShotDto> items = finalizeShots(parseShots(node), style, assetsJson, mode);
            if (items.isEmpty()) {
                throw new BusinessException("AI 返回空 items");
            }
            return items.get(0);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("AI 流式生成分镜失败: " + ex.getMessage());
        }
    }

    /** 批量划分：按参考模式写 referencePrompts 技能 */
    private String batchReferenceSkill(String mode, String promptLang) {
        if (ProjectSettingsSupport.MODE_NONE.equals(mode)) {
            return "- referencePrompts: 必须为 []（无参考模式，不要生成图片提示词）";
        }
        if (ProjectSettingsSupport.MODE_SINGLE.equals(mode)) {
            return """
                    - referencePrompts: 恰好 1 条，type 必须是 "first_frame"（故事版＝制作预演板）
                      · prompt（%s，须详尽，建议 ≥800 字）：整张 16:9 导演执行规约板（不是概念插画/海报）
                        必须遵循 skill/storyboard_production，结构齐全：
                        【项目卡】标题 / 元信息（画风·时长·优先级） / 微简述（≤40字）
                        【画面风格｜…】短名+关键词（身份参考条可用写实材质；底部叙事格优先预演可读性）
                        【版式】上约18%%身份参考条（左角色外形+简三视锁身份，右场景锚点）；下约82%%横排5格等大16:9；白/浅灰工作底、粗分隔线；须含板面标注，禁平台水印
                        【连续性】起始屏幕位置、主屏幕方向、地理锚点2–4个、起始状态、结束状态
                        【底部叙事格】每格强制五行（可压缩成段但五项齐全）：
                          SHOT NOTE → CAMERA（焦距+角度+景别+唯一运镜）→ ACTION（一个冻结瞬间）→ FOCUS（观众必须看懂什么）→ MARKS（红镜头/蓝身体/绿主动作/橙碰撞/紫节奏；无则写无）
                          节拍建议：01时空/建立 → 02站位建立 → 03核心接触 → 04情绪或台词兑现 → 05反应收束
                          禁止一格塞「先…随后…最后…」；禁止写视频秒数（秒数在 videoPrompt）
                        【板面文字】角标分镜01–05、场景名；dialogue 非空必须原样「角色名：台词」；禁止「无文字」
                        【一致性】(@名称) 与元素库同一人设/空间；参考图只锁身份不控制构图姿势
                      · note: 「故事版：导演预演板·上参考条+下五格」
                    - videoPrompt：服从故事版格序；【风格参考】一行 +【故事版绑定】+【一句话概述】+【故事版时间轴】（每段1动作1运镜）；成片无烧录字幕；禁止末尾再贴统一画风小作文
                    """.formatted(promptLang).stripTrailing();
        }
        return """
                - referencePrompts: 至少 first_frame + last_frame；prompt 写「静帧画面」——主体姿态表情、场景、景别光影、画风；禁止写运镜/时长
                """.stripTrailing();
    }

    /** 单镜重生：按参考模式写 referencePrompts 技能 */
    private String singleShotReferenceSkill(String mode, String promptLang, PromptLanguage lang) {
        if (ProjectSettingsSupport.MODE_NONE.equals(mode)) {
            return "- referencePrompts: 必须为 []（无参考模式）";
        }
        if (ProjectSettingsSupport.MODE_SINGLE.equals(mode)) {
            return """
                    - referencePrompts: 恰好 1 条（故事版＝制作预演板）；%s
                      · type 必须带双引号："first_frame"
                      · prompt（%s，须详尽，建议 ≥800 字）：本镜剧本 → 16:9 导演执行规约板（不是单帧定妆/概念插画）
                        必须遵循 skill/storyboard_production：
                        【项目卡】标题 / 元信息 / 微简述（≤40字）
                        【画面风格｜…】短名+关键词
                        【版式】上约18%%身份参考条（左角色外形锁身份，右场景锚点）；下约82%%横排5格；工作底+粗线分隔；须含标注，禁平台水印
                        【连续性】屏幕位置、屏幕方向、地理锚点、起始/结束状态
                        【逐格】每格 SHOT NOTE / CAMERA / ACTION（单冻结瞬间）/ FOCUS / MARKS
                          01建立地理 → 02站位 → 03核心接触 → 04情绪或台词 → 05收束
                          禁止一格多连动作；禁止写视频秒数
                        【板面文字】角标；dialogue 非空必须原样「角色名：内容」；禁止「无文字」
                        【一致性】(@名称) 与元素库一致；参考图只锁身份
                      · note: 「故事版：导演预演板·上参考条+下五格」
                    - videoPrompt（单图，建议 180–320 字，服从故事版）：
                      【风格参考】一行（只写一次）
                      【故事版绑定】一句
                      【一句话概述】≤40字（对齐微简述）
                      【故事版时间轴】Xs–Ys → 分镜0N｜景别｜运镜｜一个主动作｜可选台词/环境声
                        6秒：0–1.2 / 1.2–2.4 / 2.4–3.6 / 3.6–4.8 / 4.8–6.0
                        禁止空壳格号；禁止同段多运镜；禁止末尾统一画风小作文
                    """.formatted(promptLang, promptLang).stripTrailing();
        }
        String pairLen = lang.isChinese() ? "每条 prompt 80-160 字" : "each prompt 50-100 words";
        return """
                - referencePrompts: 恰好 2 条（first_frame + last_frame）；遵循 skill/first_last_frame_production；%s
                  · 均为「单帧电影静帧」：【画面风格】短名 +【景别·机位】+【场景】光向 +【主体】(@名)外形一句+冻结姿态 +【构图】
                  · 禁止运镜/时长/设定板三视图/多格故事版/白底棚拍排版
                  · first_frame：起幅冻结瞬间（动作刚起势或关键接触瞬间）
                  · last_frame：落幅冻结瞬间；必须与首帧同一人同一服装同一场景；只改姿态/光态/道具接触为主变
                  · note：中文短句，例「起幅：吞咽瞬间」「落幅：摔地瞬间」
                - type 必须带双引号："first_frame" 或 "last_frame"
                - prompt: %s
                """.formatted(pairLen, promptLang).stripTrailing();
    }

    private String extractPartialVideoPrompt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int keyIdx = raw.indexOf("\"videoPrompt\"");
        if (keyIdx < 0) {
            keyIdx = raw.indexOf("\"video_prompt\"");
        }
        if (keyIdx < 0) {
            return null;
        }
        int colon = raw.indexOf(':', keyIdx);
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) {
            i++;
        }
        if (i >= raw.length() || raw.charAt(i) != '"') {
            return null;
        }
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                switch (next) {
                    case '"', '\\', '/' -> sb.append(next);
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> sb.append(next);
                }
                i += 2;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    public String assistantChat(String context, String userMessage) {
        return assistantDecide(context, userMessage).reply();
    }

    public String streamAssistantReply(String context, String userMessage, Consumer<String> onToken) {
        String system = assistantChatSystemPrompt();
        String user = formatAssistantUserPrompt(context, userMessage);
        if (shouldUseCodexBrain()) {
            try {
                String reply = codexClient.streamAsk(system, user, onToken);
                if (reply != null && !reply.isBlank()) {
                    return reply;
                }
            } catch (Exception ex) {
                log.warn("Codex 助手大脑不可用，回退 Ollama：{}", ex.getMessage());
                if ("codex".equals(assistantProvider())) {
                    throw new BusinessException("Codex 不可用：" + ex.getMessage()
                            + "。请执行 cursor-agent login 或设置 CURSOR_API_KEY，或将 storyflow.assistant.provider 改为 hybrid/ollama。");
                }
            }
        }
        return ollamaClient.streamChatText(system, user, 2048, onToken);
    }

    public String assistantChatReply(String context, String userMessage) {
        String system = assistantChatSystemPrompt();
        String user = formatAssistantUserPrompt(context, userMessage);
        if (shouldUseCodexBrain()) {
            try {
                String reply = codexClient.chatAsk(system, user);
                if (reply != null && !reply.isBlank()) {
                    return reply;
                }
            } catch (Exception ex) {
                log.warn("Codex 助手大脑不可用，回退 Ollama：{}", ex.getMessage());
                if ("codex".equals(assistantProvider())) {
                    throw new BusinessException("Codex 不可用：" + ex.getMessage()
                            + "。请执行 cursor-agent login 或设置 CURSOR_API_KEY，或将 storyflow.assistant.provider 改为 hybrid/ollama。");
                }
            }
        }
        return ollamaClient.chatText(system, user, 2048);
    }

    private boolean shouldUseCodexBrain() {
        if (!codexClient.isEnabled()) {
            return false;
        }
        // 用户个人模型覆盖时，尊重其选择，不抢 Codex
        AiRequestOverride.Override override = AiRequestOverride.get();
        if (override != null && override.isUsable()) {
            return false;
        }
        String provider = assistantProvider();
        if ("codex".equals(provider)) {
            return true;
        }
        return "hybrid".equals(provider) && codexClient.isAvailable();
    }

    private String assistantProvider() {
        String p = properties.getAssistant().getProvider();
        return p == null || p.isBlank() ? "hybrid" : p.trim().toLowerCase(Locale.ROOT);
    }

    private String assistantChatSystemPrompt() {
        try {
            String fromCatalog = promptCatalog.system("assistant");
            if (fromCatalog != null && !fromCatalog.isBlank()) {
                return fromCatalog.trim();
            }
        } catch (Exception ignored) {
        }
        return """
                你是 StoryFlow 创作助手：结合智能推理与 StoryFlow 创作流水线。
                用中文回答，简洁、专业、可执行。

                你能帮用户想剧情、改提示词、解释分镜/资产，并引导他们使用系统能力：
                - 写/续写/重写原文（章节）
                - 根据原文生成剧本、从剧本提取资产
                - 生成/重生/删除分镜提示词（可指定编号或勾选）
                - 改写剧本条目、重做资产生图提示词、新增角色等

                重要约束：
                - 不要假装已经改动了项目数据；真正的写入由系统工具完成。
                - 若用户明确要执行上述操作，用一两句话确认意图并告诉他们可以直接下达指令（如「生成第1个分镜提示词」）。
                - 先给可落地的下一步，避免空泛套话。
                """;
    }

    private String formatAssistantUserPrompt(String context, String userMessage) {
        return """
                项目上下文：
                %s
                
                用户消息：
                %s
                """.formatted(context, userMessage);
    }

    /**
     * 轻量意图分类：判断用户是要写原文、续写章节，还是普通对话。
     * 失败时返回 null，由调用方走关键词兜底。
     */
    public WriteSourceIntent classifyWriteSourceIntent(String context, String userMessage,
                                                       int maxChapter, boolean sourceEmpty) {
        String system = """
                你是 StoryFlow 创作助手的意图分类器。只输出 JSON，不要 markdown，不要解释。
                字段：
                {
                  "intent":"chat|rewrite_chapter|append_chapters|fill_prefix|write_new|delete_chapter",
                  "targetChapter":整数或null,
                  "startChapter":整数或null,
                  "endChapter":整数或null,
                  "chapterCount":整数或null,
                  "prefixTarget":整数或null
                }
                
                意图说明：
                - chat：普通问答、介绍、建议，不涉及写小说正文
                - rewrite_chapter：重写/改写某一章，填 targetChapter
                - append_chapters：续写/追加章节；填 startChapter 与 endChapter（单章则相同）
                - fill_prefix：补全「前五章/前N章」；填 prefixTarget；已有部分章节时只补缺失章
                - write_new：从零写整本或多章；填 chapterCount；原文为空或明确要求整本重来
                - delete_chapter：删除某一章正文，填 targetChapter
                
                示例：
                - 「创作前五章」且已有2章 → fill_prefix, prefixTarget=5
                - 「第三章写一下」且尚无第3章 → append_chapters, startChapter=3, endChapter=3
                - 「重写第一章」→ rewrite_chapter, targetChapter=1
                - 「删除第七章」→ delete_chapter, targetChapter=7
                - 「写一篇校园小说2个章节」且原文空 → write_new, chapterCount=2
                - 「用一句话介绍 StoryFlow」→ chat
                """;
        String user = """
                项目上下文：
                %s
                原文是否为空：%s
                原文当前最多到第 %d 章
                
                用户消息：
                %s
                """.formatted(context, sourceEmpty, maxChapter, userMessage);
        try {
            String raw = ollamaClient.chatJson(system, user, 1024);
            JsonNode node = parseModelJson(raw);
            WriteSourceMode mode = parseWriteSourceMode(text(node, "intent", "mode", "action"));
            if (mode == null) {
                return null;
            }
            return new WriteSourceIntent(
                    mode,
                    clampChapter(nullableIntValue(node, "targetChapter", "target_chapter", "chapter")),
                    clampChapter(nullableIntValue(node, "startChapter", "start_chapter", "start")),
                    clampChapter(nullableIntValue(node, "endChapter", "end_chapter", "end")),
                    clampChapter(nullableIntValue(node, "chapterCount", "chapter_count", "count", "chapters")),
                    clampChapter(nullableIntValue(node, "prefixTarget", "prefix_target", "prefix"))
            );
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 轻量意图分类：判断用户是否要生成/重新生成分镜提示词。
     * 失败时返回 null，由调用方走关键词兜底。
     */
    public ShotsClassifyIntent classifyShotsIntent(String context, String userMessage, int totalShots) {
        String system = """
                你是 StoryFlow 创作助手的意图分类器。只输出 JSON，不要 markdown，不要解释。
                字段：
                {
                  "intent":"chat|generate_all|generate_some|regenerate_some",
                  "shotNumbers":[整数数组] 或 null,
                  "includeLast":true/false,
                  "promptLanguage":"zh"|"en"|null
                }
                
                意图说明：
                - chat：普通问答、建议、与分镜提示词无关；或用户没说清要改哪几个分镜
                - generate_all：仅当用户明确说「全部」「所有」「一键生成全部分镜」时
                - generate_some：生成指定分镜；必须填 shotNumbers（从 1 开始）；用户说「最后一个」时 includeLast=true
                - regenerate_some：重新生成指定分镜；规则同 generate_some
                
                promptLanguage：
                - 用户明确要求提示词用中文（如「我要中文」「用中文」「中文提示词」）→ "zh"
                - 用户明确要求英文（如「用英文」「english prompt」）→ "en"
                - 未提及语言 → null
                
                示例：
                - 「生成第2、3个分镜提示词」→ generate_some, shotNumbers=[2,3]
                - 「生成第一条的分镜提示词」→ generate_some, shotNumbers=[1]
                - 「第一个分镜的提示词重新给我生成，我要中文」→ regenerate_some, shotNumbers=[1], promptLanguage="zh"
                - 「生成第四和最后一个的分镜提示词」，共19个分镜 → generate_some, shotNumbers=[4], includeLast=true
                - 「把分镜提示词全部生成」→ generate_all
                - 「生成分镜提示词」（未指定编号）→ chat
                - 「重写第5个分镜的 prompt」→ regenerate_some, shotNumbers=[5]
                - 「这个项目怎么写剧本」→ chat
                - 「第三个分镜的剧本重新生成」→ chat（目标是剧本，不是提示词）
                - 「重写第2条剧本」→ chat
                - 「请根据当前原文生成剧本」→ chat
                
                注意：
                - shotNumbers 只填用户明确提到的序号；includeLast=true 表示还要包含最后一个分镜（序号=totalShots）。
                - 未明确「全部/所有」且未给出编号时，必须返回 chat，禁止猜测 generate_all。
                - 用户说「剧本」且未说「提示词/prompt」时必须返回 chat，禁止当成 regenerate_some。
                """;
        String user = """
                项目上下文：
                %s
                当前分镜总数（无分镜时按剧本条目数估算）：%d
                
                用户消息：
                %s
                """.formatted(context, totalShots, userMessage);
        try {
            String raw = ollamaClient.chatJson(system, user, 1024);
            JsonNode node = parseModelJson(raw);
            ShotsClassifyMode mode = parseShotsClassifyMode(text(node, "intent", "mode", "action"));
            if (mode == null) {
                return null;
            }
            List<Integer> shotNumbers = parseShotNumberList(node.get("shotNumbers"));
            if (shotNumbers.isEmpty()) {
                shotNumbers = parseShotNumberList(node.get("shots"));
            }
            boolean includeLast = node.has("includeLast") && !node.get("includeLast").isNull()
                    && node.get("includeLast").asBoolean();
            PromptLanguage promptLanguage = parsePromptLanguage(text(node, "promptLanguage", "language", "lang"));
            return new ShotsClassifyIntent(mode, shotNumbers, includeLast, promptLanguage);
        } catch (Exception ex) {
            return null;
        }
    }

    private PromptLanguage parsePromptLanguage(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase();
        if (s.startsWith("zh") || s.contains("中文") || "chinese".equals(s) || "cn".equals(s)) {
            return PromptLanguage.ZH;
        }
        if (s.startsWith("en") || s.contains("英文") || s.contains("英语") || "english".equals(s)) {
            return PromptLanguage.EN;
        }
        return null;
    }

    private String shotGenerateSystemPrompt(PromptLanguage language) {
        return shotGenerateSystemPrompt(language, ProjectSettingsSupport.MODE_FIRST_LAST);
    }

    private String shotGenerateSystemPrompt(PromptLanguage language, String refPromptMode) {
        String base = getTemplate("shot_generate");
        String mode = ProjectSettingsSupport.normalizeMode(refPromptMode);
        String storyboardSkill = ProjectSettingsSupport.MODE_SINGLE.equals(mode)
                ? "\n\n" + promptCatalog.storyboardProductionGuide()
                : "";
        String flfSkill = ProjectSettingsSupport.MODE_FIRST_LAST.equals(mode)
                ? "\n\n" + promptCatalog.firstLastFrameProductionGuide()
                : "";
        String modeSkill = switch (mode) {
            case ProjectSettingsSupport.MODE_SINGLE -> """
                    
                    【当前模式｜单图 · 制作预演板】
                    - referencePrompts 恰好 1 条 type="first_frame"；完整规约见下方「故事版｜导演执行规约」
                    - 上约18%身份参考条 + 下约82%横排五格；整条建议 ≥800 字
                    - 每格强制 SHOT NOTE→CAMERA→ACTION→FOCUS→MARKS；ACTION 每格一个冻结瞬间
                    - 须写清微简述、屏幕方向、地理锚点、起止状态；板面文字含角标/场景名/台词原文
                    - 三视图只许顶部参考条；禁止概念插画/海报/设定表拼贴
                    - videoPrompt 服从故事版格序：【风格参考】一行 + 绑定 + 概述 + 时间轴（每段1动作1运镜）；禁止末尾画风小作文；成片无烧录字幕
                    """;
            case ProjectSettingsSupport.MODE_NONE -> """
                    
                    【当前模式｜无参考 · 纯文生视频】
                    - referencePrompts 必须为 []，不要生成任何图片提示词
                    - videoPrompt 建议 80–140 字，六段式：
                      【风格参考】一行短句（只写一次）
                      【一句话概述】≤40字
                      【动作】先…随后…最后…（忠实剧本）
                      【镜头】景别 + 唯一运镜
                      【画面】时间；地点；人物（可用 (@资产名)）
                      【节奏】时长：X秒（X=6|10）；台词时段固定；无烧录字幕
                    - 禁止：【画面风格】长段、【故事版绑定/时间轴】、【首尾帧驱动】、外形小作文
                    """;
            default -> """
                    
                    【当前模式｜首尾帧 · FLF】
                    - 每个分镜恰好 first_frame + last_frame 两条「单帧电影静帧」；完整规约见下方「首尾帧｜静帧生产规约」
                    - 静帧 80–160 字：景别机位+场景光+主体冻结姿态；禁止运镜/时长/设定板三视图
                    - 两帧同人同服同场景；尾帧相对首帧只改姿态/光态主变，须物理可插值
                    - videoPrompt：【首尾帧驱动】只写过渡 + 唯一运镜 + 时长；禁止复述静帧
                    """;
        };
        // 默认中文；仅当用户明确要求英文时走英文分支
        if (language != null && !language.isChinese()) {
            return base + modeSkill + storyboardSkill + flfSkill + """
                    
                    语言要求：
                    - videoPrompt 使用英文
                    - referencePrompts 的 prompt 使用英文，note 用中文
                    - type 必须写成 JSON 字符串："first_frame" / "last_frame" / "other"（必须带双引号）
                    - Strictly follow the user-specified visual style; never default to Japanese anime unless that style is selected.
                    """;
        }
        String actionSkill = ProjectSettingsSupport.MODE_SINGLE.equals(mode)
                ? """
                
                【动作纯度｜单图】故事版每格 / 视频每秒段只写一个冻结可读瞬间；禁止把「先…随后…最后…」塞进同一格或同一秒段
                """
                : ProjectSettingsSupport.MODE_NONE.equals(mode)
                ? """
                
                【动作链｜无参考】核心动作写成「先…随后…最后…」一条连续链，忠实剧本；禁止空镜空转与堆多种运镜
                """
                : """
                
                【动作链｜首尾帧】核心动作可写成「先…随后…最后…」，避免单点动词敷衍
                """;
        return base + modeSkill + storyboardSkill + flfSkill + """
                
                【强制】中文提示词（默认）：
                - videoPrompt / referencePrompts.prompt 必须全部中文；禁止英文堆砌
                - note 用中文；type 必须带双引号："first_frame" / "last_frame" / "other"
                - 严格遵循用户指定画风
                
                【图/视分工】
                - referencePrompts：单图=制作预演板；首尾帧=单帧电影静帧（禁止运镜时长与设定板）
                - videoPrompt：动态——首尾帧写过渡；单图服从故事版；只写运动/运镜/声音变化
                
                """ + cameraLanguageSkill() + actionSkill + """
                
                【六维一致】人物映射资产库；时空/光线与相邻镜衔接；关键道具状态延续；落幅承接上一镜结束态；
                仅说话角色可张嘴且口型自然；未说话角色闭嘴；五官发型服饰全程稳定不换脸；违禁血腥低俗用中性词改写
                
                【忠实原文】不添加剧本未提及的情节、人物心理或道具
                """;
    }

    /** 将画风预设展开为中文强约束（禁止英文画风说明） */
    private String styleDirective(String style) {
        String name = style == null || style.isBlank() ? "日漫赛璐璐" : style.trim();
        String detail = chineseStylePrompt(name);
        return """
                【画面风格｜%s】
                %s
                要求：
                - videoPrompt、referencePrompts.prompt 必须用中文撰写
                - 开头先写「【画面风格｜%s】」+ 画风关键词，再写镜头与剧情
                - 若与资产提示词冲突，以本画风为准
                - 禁止输出未选定的画风；禁止整段英文提示词
                """.formatted(name, detail, name);
    }

    /**
     * 分镜生成用画风指令：静帧/故事版可用【画面风格】；videoPrompt 一律只用【风格参考】一行。
     */
    private String styleDirectiveForVideoModes(String style, String mode) {
        String name = style == null || style.isBlank() ? "日漫赛璐璐" : style.trim();
        String detail = chineseStylePrompt(name);
        String refRule = switch (ProjectSettingsSupport.normalizeMode(mode)) {
            case ProjectSettingsSupport.MODE_SINGLE ->
                    "- referencePrompts.prompt（故事版）：开头写「【画面风格｜" + name + "】」+ 关键词，再写项目卡/连续性/逐格规约";
            case ProjectSettingsSupport.MODE_NONE ->
                    "- referencePrompts 必须为 []（无参考）";
            default ->
                    "- referencePrompts.prompt（首尾帧静帧）：开头写「【画面风格｜" + name + "】」+ 关键词，再写景别/场景/冻结姿态；禁止运镜与时长词";
        };
        return """
                【画面风格｜%s】
                %s
                要求：
                - 全部中文；以本画风为准；禁止未选定画风与整段英文
                %s
                - videoPrompt：开头仅一行「【风格参考】」短句（可摘自画风关键词），禁止再写【画面风格】长段，禁止末尾「统一画风」小作文
                """.formatted(name, detail, refRule);
    }

    /** @deprecated 用 {@link #styleDirectiveForVideoModes} */
    private String styleDirectiveForSingle(String style) {
        return styleDirectiveForVideoModes(style, ProjectSettingsSupport.MODE_SINGLE);
    }

    private ShotsClassifyMode parseShotsClassifyMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase()) {
            case "chat", "none", "qa", "question" -> ShotsClassifyMode.CHAT;
            case "generate_all", "all" -> ShotsClassifyMode.GENERATE_ALL;
            case "generate", "generate_some", "generate_one", "some", "partial" -> ShotsClassifyMode.GENERATE_SOME;
            case "regenerate_some", "regenerate_one", "regenerate", "redo", "rewrite" -> ShotsClassifyMode.REGENERATE_SOME;
            default -> null;
        };
    }

    private List<Integer> parseShotNumberList(JsonNode node) {
        List<Integer> result = new ArrayList<>();
        if (node == null || node.isNull() || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            if (item.isNumber()) {
                addShotNumber(result, item.asInt());
            } else if (item.isTextual()) {
                try {
                    addShotNumber(result, Integer.parseInt(item.asText().trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }

    private void addShotNumber(List<Integer> result, int n) {
        if (n >= 1 && n <= 100 && !result.contains(n)) {
            result.add(n);
        }
    }

    private WriteSourceMode parseWriteSourceMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase()) {
            case "chat", "none", "qa", "question" -> WriteSourceMode.CHAT;
            case "rewrite_chapter", "rewrite" -> WriteSourceMode.REWRITE_CHAPTER;
            case "append_chapters", "append", "append_chapter", "continue" -> WriteSourceMode.APPEND_CHAPTERS;
            case "fill_prefix", "fill", "prefix", "complete_prefix" -> WriteSourceMode.FILL_PREFIX;
            case "write_new", "write", "new", "set_source" -> WriteSourceMode.WRITE_NEW;
            case "delete_chapter", "delete", "remove_chapter", "remove" -> WriteSourceMode.DELETE_CHAPTER;
            default -> null;
        };
    }

    private Integer clampChapter(Integer value) {
        if (value == null) {
            return null;
        }
        return Math.min(Math.max(value, 1), 20);
    }

    public AssistantDecision assistantDecide(String context, String userMessage) {
        String system = """
                你是 StoryFlow 项目助手。必须输出 JSON：
                {"reply":"给用户看的中文回复","actions":[{"type":"set_source|append_source|none","content":"..."}]}
                
                规则：
                1. 用户要求写小说/填原文/创作章节时，必须用 type=set_source，把完整小说正文写入 content（含章节标题与正文），不要只给大纲或分镜。
                2. 用户要求追加内容时用 append_source。
                3. 仅问答、建议时用 type=none，content 可空。
                4. reply 简短说明你做了什么；真正的小说正文放在 actions.content，不要只放在 reply。
                """;
        String user = """
                项目上下文：
                %s
                
                用户消息：
                %s
                """.formatted(context, userMessage);
        try {
            String raw = ollamaClient.chatJson(system, user, 8192);
            JsonNode node = parseModelJson(raw);
            String reply = text(node, "reply", "message", "content");
            List<AssistantAction> actions = new ArrayList<>();
            JsonNode actionsNode = node.get("actions");
            if (actionsNode != null && actionsNode.isArray()) {
                for (JsonNode action : actionsNode) {
                    actions.add(new AssistantAction(
                            text(action, "type"),
                            text(action, "content", "text", "source")
                    ));
                }
            }
            if (reply.isBlank()) {
                reply = "已处理你的请求。";
            }
            return new AssistantDecision(reply, actions);
        } catch (Exception ex) {
            return new AssistantDecision("助手处理失败：" + ex.getMessage(), List.of());
        }
    }

    /**
     * 按章节流式生成小说正文；onToken 接收要展示给用户的增量文本，onStatus 接收进度提示。
     */
    public String streamNovelByChapters(String userRequest, String style, int chapterCount,
                                        java.util.function.Consumer<String> onToken,
                                        java.util.function.Consumer<String> onStatus) {
        return streamNovelByChapters(userRequest, style, chapterCount, onToken, onStatus, null);
    }

    public String streamNovelByChapters(String userRequest, String style, int chapterCount,
                                        java.util.function.Consumer<String> onToken,
                                        java.util.function.Consumer<String> onStatus,
                                        java.util.function.BooleanSupplier isCancelled) {
        String bookTitle = "校园故事";
        List<ChapterPlan> plans = new ArrayList<>();

        if (onStatus != null) {
            onStatus.accept("正在规划 " + chapterCount + " 章大纲…");
        }

        try {
            String outlineSystem = "你是校园小说编剧，只输出 JSON，不要 markdown。";
            String outlineUser = """
                    用户需求：%s
                    风格：%s
                    请规划 %d 章校园小说。
                    若用户未指定主角姓名，请根据需求自拟人名；每次创作可用不同名字，不要总固定成同一个主角（例如不要总是林风）。
                    严格输出：{"title":"书名","chapters":[{"index":1,"title":"章节标题","summary":"50字摘要"}]}
                    """.formatted(userRequest, style, chapterCount);
            String raw = ollamaClient.chatJson(outlineSystem, outlineUser, 2048);
            JsonNode outline = parseModelJson(raw);
            String title = text(outline, "title", "name");
            if (!title.isBlank()) {
                bookTitle = title;
            }
            JsonNode chapters = outline.get("chapters");
            if (chapters != null && chapters.isArray()) {
                for (JsonNode chapter : chapters) {
                    plans.add(new ChapterPlan(
                            intValue(chapter, plans.size() + 1, "index", "no", "order"),
                            text(chapter, "title", "name"),
                            text(chapter, "summary", "desc", "description")
                    ));
                }
            }
        } catch (Exception ex) {
            // fall through
        }

        if (plans.isEmpty()) {
            for (int i = 1; i <= chapterCount; i++) {
                plans.add(new ChapterPlan(i, "第" + i + "章", "围绕校园生活推进第" + i + "章剧情"));
            }
        } else if (plans.size() < chapterCount) {
            for (int i = plans.size() + 1; i <= chapterCount; i++) {
                plans.add(new ChapterPlan(i, "第" + i + "章", "承接上文推进第" + i + "章剧情"));
            }
        } else if (plans.size() > chapterCount) {
            plans = new ArrayList<>(plans.subList(0, chapterCount));
        }

        StringBuilder novel = new StringBuilder();
        String header = "《" + bookTitle + "》\n\n";
        novel.append(header);
        if (onToken != null) {
            onToken.accept(header);
        }

        for (ChapterPlan plan : plans) {
            if (isCancelled != null && isCancelled.getAsBoolean()) {
                break;
            }
            String title = plan.title().isBlank() ? ("第" + plan.index() + "章") : plan.title();
            String summary = plan.summary().isBlank() ? "承接上文推进剧情" : plan.summary();
            if (onStatus != null) {
                onStatus.accept("正在生成第 " + plan.index() + "/" + plans.size() + " 章：《" + title + "》");
            }
            String chapterHeader = "第" + plan.index() + "章 " + title + "\n\n";
            novel.append(chapterHeader);
            if (onToken != null) {
                onToken.accept(chapterHeader);
            }

            String body = streamWriteChapter(bookTitle, plan.index(), title, summary, userRequest, style,
                    novel.toString(), onToken);
            if (isCancelled != null && isCancelled.getAsBoolean()) {
                break;
            }
            if (body == null || body.isBlank()) {
                body = "（本章生成失败，请稍后重试）";
                if (onToken != null) {
                    onToken.accept(body);
                }
            }
            novel.append(body.trim()).append("\n\n");
            if (onToken != null) {
                onToken.accept("\n\n");
            }
        }
        return novel.toString().trim();
    }

    private String streamWriteChapter(String bookTitle, int no, String title, String summary,
                                      String userRequest, String style, String previousText,
                                      java.util.function.Consumer<String> onToken) {
        String system = "你是小说作者。只输出该章正文，不要标题，不要大纲，不要解释，不要思考过程。人物姓名须与前文及大纲摘要保持一致，不得擅自改名。";
        String user = """
                书名：%s
                风格：%s
                用户需求：%s
                当前章节：第%d章《%s》
                本章摘要：%s
                前文（供连贯，可截断）：
                %s
                
                请写本章完整小说正文，约350-500字，有人物对话与场景描写。直接写正文。须与前文人名一致，新故事也不要总用同一个固定主角名。
                """.formatted(
                bookTitle,
                style,
                userRequest,
                no,
                title,
                summary,
                previousText.length() > 800 ? previousText.substring(previousText.length() - 800) : previousText
        );
        try {
            // 短章 + 较低 num_predict，避免 qwen3.5 拖很久
            return ollamaClient.streamChatText(system, user, 900, onToken);
        } catch (Exception ex) {
            return "";
        }
    }

    /**
     * 按章节生成校园小说正文，直接用于写入原文。
     */
    public String generateNovelByChapters(String userRequest, String style, int chapterCount) {
        return streamNovelByChapters(userRequest, style, chapterCount, null, null);
    }

    /**
     * 在已有原文上续写章节，只返回新增正文（用于 append，不会重写前文）。
     */
    public String streamAppendChapters(String userRequest, String style, String existingText,
                                       int startChapter, int chapterCount,
                                       java.util.function.Consumer<String> onToken,
                                       java.util.function.Consumer<String> onStatus) {
        return streamAppendChapters(userRequest, style, existingText, startChapter, chapterCount, onToken, onStatus, null);
    }

    public String streamAppendChapters(String userRequest, String style, String existingText,
                                       int startChapter, int chapterCount,
                                       java.util.function.Consumer<String> onToken,
                                       java.util.function.Consumer<String> onStatus,
                                       java.util.function.BooleanSupplier isCancelled) {
        int count = Math.min(Math.max(chapterCount, 1), 20);
        int start = Math.max(startChapter, 1);
        String existing = existingText == null ? "" : existingText;
        String bookTitle = extractBookTitle(existing);

        if (onStatus != null) {
            onStatus.accept("正在规划续写第 " + start + (count > 1 ? ("–" + (start + count - 1)) : "") + " 章…");
        }

        List<ChapterPlan> plans = new ArrayList<>();
        try {
            String outlineSystem = "你是校园小说编剧，只输出 JSON，不要 markdown。";
            String outlineUser = """
                    用户需求：%s
                    风格：%s
                    已有原文摘要（供连贯）：
                    %s
                    
                    请只规划第 %d 章到第 %d 章（共 %d 章），承接上文，不要重复已有情节。
                    严格输出：{"chapters":[{"index":%d,"title":"章节标题","summary":"50字摘要"}]}
                    """.formatted(
                    userRequest,
                    style,
                    existing.length() > 1500 ? existing.substring(existing.length() - 1500) : existing,
                    start,
                    start + count - 1,
                    count,
                    start
            );
            String raw = ollamaClient.chatJson(outlineSystem, outlineUser, 2048);
            JsonNode outline = parseModelJson(raw);
            JsonNode chapters = outline.get("chapters");
            if (chapters != null && chapters.isArray()) {
                for (JsonNode chapter : chapters) {
                    plans.add(new ChapterPlan(
                            intValue(chapter, plans.size() + start, "index", "no", "order"),
                            text(chapter, "title", "name"),
                            text(chapter, "summary", "desc", "description")
                    ));
                }
            }
        } catch (Exception ignored) {
            // fall through
        }

        if (plans.isEmpty()) {
            for (int i = 0; i < count; i++) {
                int no = start + i;
                plans.add(new ChapterPlan(no, "第" + no + "章", "承接上文推进第" + no + "章剧情"));
            }
        } else if (plans.size() > count) {
            plans = new ArrayList<>(plans.subList(0, count));
        }
        while (plans.size() < count) {
            int no = start + plans.size();
            plans.add(new ChapterPlan(no, "第" + no + "章", "承接上文推进第" + no + "章剧情"));
        }

        StringBuilder appended = new StringBuilder();
        String contextSoFar = existing;
        for (int i = 0; i < plans.size(); i++) {
            if (isCancelled != null && isCancelled.getAsBoolean()) {
                break;
            }
            ChapterPlan plan = plans.get(i);
            int no = start + i;
            String title = plan.title().isBlank() ? ("第" + no + "章") : plan.title();
            String summary = plan.summary().isBlank() ? "承接上文推进剧情" : plan.summary();
            if (onStatus != null) {
                onStatus.accept("正在续写第 " + no + " 章：《" + title + "》");
            }
            String chapterHeader = "第" + no + "章 " + title + "\n\n";
            appended.append(chapterHeader);
            if (onToken != null) {
                onToken.accept(chapterHeader);
            }

            String body = streamWriteChapter(bookTitle, no, title, summary, userRequest, style,
                    contextSoFar, onToken);
            if (isCancelled != null && isCancelled.getAsBoolean()) {
                break;
            }
            if (body == null || body.isBlank()) {
                body = "（本章生成失败，请稍后重试）";
                if (onToken != null) {
                    onToken.accept(body);
                }
            }
            String chapterText = body.trim() + "\n\n";
            appended.append(chapterText);
            contextSoFar = contextSoFar + "\n\n" + chapterHeader + chapterText;
            if (onToken != null) {
                onToken.accept("\n\n");
            }
        }
        return appended.toString().trim();
    }

    private String extractBookTitle(String existingText) {
        if (existingText == null || existingText.isBlank()) {
            return "校园故事";
        }
        Matcher m = Pattern.compile("《([^》]+)》").matcher(existingText);
        if (m.find()) {
            return m.group(1).trim();
        }
        String first = existingText.lines().map(String::trim).filter(s -> !s.isEmpty()).findFirst().orElse("");
        if (!first.isBlank() && first.length() <= 30 && !first.startsWith("第")) {
            return first.replaceAll("^[#\\s]+", "");
        }
        return "校园故事";
    }

    private String writeChapter(String bookTitle, int no, String title, String summary,
                                String userRequest, String style, String previousText) {
        return streamWriteChapter(bookTitle, no, title, summary, userRequest, style, previousText, null);
    }

    public String regenerateAssetPrompt(AssetDto asset, String style) {
        String styleName = style == null || style.isBlank() ? "日漫赛璐璐" : style.trim();
        String type = asset.type() == null ? "" : asset.type().trim().toLowerCase();
        StylePack pack = promptCatalog.stylePack(styleName);
        String system;
        String styleLine;
        String user;
        if ("character".equals(type)) {
            system = """
                    你是角色设定图提示词工程师。只输出一条中文提示词，不要引号、编号、解释。
                    必须把「介绍」里的外形信息写进提示词；禁止只输出画质形容词堆砌。
                    画风必须严格为「%s」，禁止混写其他画风（不要写「厚涂转写实」「日漫转真人」这类拼接）。
                    """.formatted(styleName) + assetImagePromptGuide(styleName);
            styleLine = chineseStylePrompt(styleName);
            user = """
                    %s
                    名称：%s
                    介绍（必须转写为可见外形，功法设定要改成服装/气质外观）：%s
                    风格包前置：%s
                    请严格按此设定板骨架填写一条提示词（约 100-180 字；可微调但须保留构图结构）：
                    %s
                    """.formatted(
                    styleLine,
                    asset.name(),
                    asset.description(),
                    pack.characterPrefix(),
                    pack.renderSheet(asset.name(), "【身份锚点+造型五槽：年龄体型脸型眼睛肤色发型+1标志物；上装→内搭→下装→鞋→最多1配饰；禁好看帅气/手持道具】"));
        } else if ("prop".equals(type)) {
            system = """
                    你是道具参考图提示词工程师。只输出一条中文提示词，不要引号、编号、解释。
                    只写道具外形材质；画面禁止任何人物。画风必须严格为「%s」。
                    """.formatted(styleName);
            styleLine = pack.propPrefix().isBlank() ? "【画面风格｜" + styleName + "】" : pack.propPrefix();
            user = """
                    %s
                    名称：%s
                    介绍：%s
                    输出：道具多角度参考表，正面侧面俯视同页，白底，【材质外形】，禁止人物，%s，%s
                    """.formatted(styleLine, asset.name(), asset.description(),
                    styleName, assetQualitySuffix("prop"));
        } else {
            // scene：勿注入角色设定 guide / 肤质向总览，避免空镜带人
            system = """
                    你是场景环境设定图提示词工程师。只输出一条中文提示词，不要引号、编号、解释。
                    只写空间结构、材质、光影、天气与氛围；必须是空镜环境。
                    画面中禁止任何人物、路人、剪影、人脸、肢体；inset 小图也须无人。
                    禁止写肤质/毛孔/五官/服装穿搭等人物词。画风必须严格为「%s」。
                    """.formatted(styleName);
            styleLine = pack.scenePrefix().isBlank()
                    ? "【画面风格｜" + styleName + "】"
                    : "【画面风格｜" + styleName + "】" + pack.scenePrefix();
            user = """
                    %s
                    名称：%s
                    介绍（只保留环境信息，忽略人名与人物动作）：%s
                    输出：空镜环境设定主视觉，【空间材质光影氛围】，可带无人小 inset，%s，%s
                    """.formatted(styleLine, asset.name(), asset.description(),
                    styleName, assetQualitySuffix("scene"));
        }
        String raw = ollamaClient.chatText(system, user).replace("\"", "").trim();
        if ("character".equals(type) && !looksLikeCharacterSheetPrompt(raw)) {
            return fallbackCharacterSheetPrompt(asset.name(), asset.description(), styleName);
        }
        if ("scene".equals(type)) {
            raw = ensureSceneEmptyOfPeople(raw);
        }
        return promptPipeline.normalizeAssetPersist(
                ensureStyleKeyword(raw, styleName), type, styleName);
    }

    /** 场景提示词兜底：若缺空镜约束则补上，并去掉常见人物诱导词残留说明 */
    static String ensureSceneEmptyOfPeople(String prompt) {
        String text = prompt == null ? "" : prompt.trim();
        if (text.isEmpty()) {
            return "空镜环境设定主视觉，禁止任何人物路人剪影";
        }
        if (!text.contains("空镜") && !text.contains("无人") && !text.contains("禁止人物")) {
            text = "空镜无人，禁止人物路人剪影。" + text;
        }
        return text;
    }

    /** @see PromptPrinciples#ensureStyleKeyword */
    static String ensureStyleKeyword(String prompt, String styleName) {
        return PromptPrinciples.ensureStyleKeyword(prompt, styleName);
    }

    private List<ShotDto> finalizeShots(List<ShotDto> raw, String style, String assetsJson, String refMode) {
        if (raw == null || raw.isEmpty()) {
            return raw == null ? List.of() : raw;
        }
        List<String> charPrompts = extractCharacterImagePrompts(assetsJson);
        PromptPipeline.Context ctx = PromptPipeline.Context.shotPersistFromPrompts(style, charPrompts);
        String mode = ProjectSettingsSupport.normalizeMode(refMode);
        return raw.stream().map(s -> normalizeShotDto(s, ctx, mode)).toList();
    }

    private ShotDto normalizeShotDto(ShotDto shot, PromptPipeline.Context ctx, String mode) {
        String videoRaw = PromptPrinciples.stripDramaStyleBlockForVideo(shot.videoPrompt());
        if (ProjectSettingsSupport.MODE_NONE.equals(mode) && videoRaw != null) {
            // 无参考不应残留图生模板标签
            videoRaw = videoRaw
                    .replaceAll("(?m)^【故事版绑定】[^\\n]*\\n?", "")
                    .replaceAll("(?m)^【故事版时间轴】[^\\n]*\\n?", "")
                    .replaceAll("(?m)^【首尾帧驱动】[^\\n]*\\n?", "")
                    .replaceAll("\\n{3,}", "\n\n")
                    .trim();
        } else if (ProjectSettingsSupport.MODE_FIRST_LAST.equals(mode) && videoRaw != null) {
            // 首尾帧视频只写过渡：去故事版标签 → 清洗设定板污染 → 收成过渡结构
            videoRaw = videoRaw
                    .replaceAll("(?m)^【故事版绑定】[^\\n]*\\n?", "")
                    .replaceAll("(?m)^【故事版时间轴】[^\\n]*\\n?", "")
                    .replaceAll("\\n{3,}", "\n\n")
                    .trim();
            videoRaw = PromptPrinciples.sanitizeFlfVideoPrompt(videoRaw);
        }
        String video = promptPipeline.finalize(PromptPipeline.Target.SHOT_PERSIST, videoRaw, ctx);
        List<String> charHints = ctx.characterPromptHints();
        PromptPipeline.Context stillCtx = PromptPipeline.Context.shotPersistStillFromPrompts(
                ctx.stylePreset(), charHints);
        boolean flfMode = ProjectSettingsSupport.MODE_FIRST_LAST.equals(mode);
        List<ReferencePromptDto> refs = new ArrayList<>();
        if (shot.referencePrompts() != null) {
            for (ReferencePromptDto ref : shot.referencePrompts()) {
                // 单图模式 first_frame=故事版，不得按静帧剥运镜
                boolean still = flfMode && isStillFrameRefType(ref.type());
                String prompt = promptPipeline.finalize(
                        PromptPipeline.Target.SHOT_PERSIST,
                        ref.prompt(),
                        still ? stillCtx : ctx);
                refs.add(new ReferencePromptDto(ref.type(), prompt, ref.note()));
            }
        }
        if (ProjectSettingsSupport.MODE_NONE.equals(mode)) {
            refs = List.of();
        }
        return new ShotDto(shot.scriptIndex(), video, refs, shot.linkedAssets());
    }

    private static boolean isStillFrameRefType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String t = type.trim().toLowerCase();
        return "first_frame".equals(t) || "last_frame".equals(t) || "other".equals(t);
    }

    private List<String> extractCharacterImagePrompts(String assetsJson) {
        List<String> prompts = new ArrayList<>();
        if (assetsJson == null || assetsJson.isBlank()) {
            return prompts;
        }
        try {
            JsonNode arr = objectMapper.readTree(assetsJson);
            if (!arr.isArray()) {
                return prompts;
            }
            for (JsonNode item : arr) {
                String type = text(item, "type", "category");
                if (type == null || !"character".equalsIgnoreCase(type.trim())) {
                    continue;
                }
                String p = text(item, "imagePrompt", "prompt", "image_prompt");
                if (!p.isBlank()) {
                    prompts.add(p);
                }
            }
        } catch (Exception ignored) {
            // 解析失败时不阻断分镜生成
        }
        return prompts;
    }

    /** 按用户指令重写单条剧本，输出 items 仅 1 条 */
    public ScriptDto rewriteScriptEntry(ScriptDto current, String style, String instruction) {
        return scriptLlm.rewriteScriptEntry(current, style, instruction);
    }

    /** 按用户指令重写资产介绍与生图提示词 */
    public AssetDto rewriteAsset(AssetDto current, String style, String instruction) {
        String system = getTemplate("asset_extract");
        String styleName = style == null || style.isBlank() ? "日漫赛璐璐" : style.trim();
        String styleLine = chineseStylePrompt(styleName);
        String user = """
                【强制画风｜%s】
                %s
                用户修改要求：%s
                请基于当前资产重写，只输出 1 条。严格 JSON：{"items":[{...}]}
                字段：type(character|scene|prop), name, description(中文), imagePrompt(中文生图提示词，禁止英文)
                imagePrompt 末尾必须含「%s」，禁止混入其他画风名。
                %s
                
                当前资产：
                {"type":%s,"name":%s,"description":%s,"imagePrompt":%s}
                """.formatted(
                styleName,
                styleLine,
                instruction == null || instruction.isBlank()
                        ? "优化介绍与生图提示词（人物须为角色设定参考图：左正脸特写+右正侧背全身）"
                        : instruction,
                styleName,
                assetImagePromptGuide(styleName),
                quoteJson(current.type()),
                quoteJson(current.name()),
                quoteJson(current.description()),
                quoteJson(current.imagePrompt())
        );
        JsonNode node = callJsonWithRetry(system, user);
        List<AssetDto> items = parseAssets(node, styleName);
        if (items.isEmpty()) {
            throw new BusinessException("AI 未返回资产");
        }
        return items.get(0);
    }

    private String quoteJson(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quoteJson(values.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    private JsonNode callJsonWithRetry(String system, String user) {
        return callJsonWithRetry(system, user, false);
    }

    /** 资产分类提取：合法空数组视为成功 */
    private JsonNode callJsonAllowEmpty(String system, String user) {
        return callJsonWithRetry(system, user, true);
    }

    private JsonNode callJsonWithRetry(String system, String user, boolean allowEmptyItems) {
        int maxRetries = properties.getOllama().getMaxRetries();
        Exception last = null;
        for (int i = 0; i < maxRetries; i++) {
            try {
                String raw = ollamaClient.chatJson(system, user);
                JsonNode node = parseModelJson(raw);
                JsonNode items = itemsNode(node);
                if (items.isArray() && (allowEmptyItems || !items.isEmpty())) {
                    return node;
                }
                last = new BusinessException("AI 返回空 items");
            } catch (Exception ex) {
                last = ex;
            }
        }
        throw new BusinessException("AI 生成失败，已重试 " + maxRetries + " 次: " +
                (last != null ? last.getMessage() : "未知错误"));
    }

    /** 提取并容错解析模型 JSON（修复未加引号的 first_frame、截断未闭合等） */
    private JsonNode parseModelJson(String raw) throws Exception {
        String json = repairLooseJson(extractJson(raw));
        try {
            return objectMapper.readTree(json);
        } catch (Exception first) {
            String closed = closeTruncatedJson(json);
            if (!closed.equals(json)) {
                try {
                    return objectMapper.readTree(repairLooseJson(closed));
                } catch (Exception ignored) {
                    // fall through
                }
            }
            String retry = repairLooseJson(json);
            if (!retry.equals(json)) {
                return objectMapper.readTree(retry);
            }
            throw first;
        }
    }

    /**
     * 修复模型常见的松散 JSON：
     * - "type": first_frame → "type": "first_frame"
     * - {type: "..." → {"type": "..."
     */
    private String repairLooseJson(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        String fixed = json.trim();
        // 去掉可能残留的思考标签
        fixed = fixed.replaceAll("(?s)<think>.*?</think>", "");
        fixed = UNQUOTED_REF_TYPE.matcher(fixed).replaceAll("$1\"$2\"$3");
        fixed = UNQUOTED_TYPE_KEY.matcher(fixed).replaceAll("$1\"$2\"$3");
        // 尾部多余逗号
        fixed = fixed.replaceAll(",\\s*([}\\]])", "$1");
        return fixed;
    }

    /** 补全因 max_tokens 截断而未闭合的字符串 / 数组 / 对象 */
    private String closeTruncatedJson(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder(json.trim());
        boolean inString = false;
        boolean escape = false;
        Deque<Character> stack = new ArrayDeque<>();
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                    continue;
                }
                if (c == '\\') {
                    escape = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{' || c == '[') {
                stack.push(c);
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
            }
        }
        if (inString) {
            sb.append('"');
        }
        // 去掉闭合前的尾逗号
        int end = sb.length() - 1;
        while (end >= 0 && Character.isWhitespace(sb.charAt(end))) {
            end--;
        }
        if (end >= 0 && sb.charAt(end) == ',') {
            sb.deleteCharAt(end);
        }
        while (!stack.isEmpty()) {
            char open = stack.pop();
            sb.append(open == '{' ? '}' : ']');
        }
        return sb.toString();
    }

    private String getTemplate(String category) {
        return promptCatalog.system(category);
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        Matcher matcher = JSON_BLOCK.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        int startObj = raw.indexOf('{');
        int startArr = raw.indexOf('[');
        int start;
        if (startObj < 0) {
            start = startArr;
        } else if (startArr < 0) {
            start = startObj;
        } else {
            start = Math.min(startObj, startArr);
        }
        if (start < 0) {
            return raw.trim();
        }
        char open = raw.charAt(start);
        char close = open == '[' ? ']' : '}';
        int end = raw.lastIndexOf(close);
        if (end > start) {
            return raw.substring(start, end + 1);
        }
        return raw.trim();
    }

    private JsonNode itemsNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return objectMapper.createArrayNode();
        }
        if (node.isArray()) {
            return node;
        }
        if (node.has("items") && node.get("items").isArray()) {
            return node.get("items");
        }
        // common alternate keys from models
        for (String key : List.of("scripts", "assets", "shots", "data", "result")) {
            if (node.has(key) && node.get(key).isArray()) {
                return node.get(key);
            }
        }
        return objectMapper.createArrayNode();
    }

    private List<ScriptDto> parseScripts(JsonNode node) {
        List<ScriptDto> result = new ArrayList<>();
        for (JsonNode item : itemsNode(node)) {
            result.add(new ScriptDto(
                    text(item, "time", "timeLabel", "时段"),
                    text(item, "location", "地点"),
                    stringList(item, "characters", "character", "人物"),
                    text(item, "action", "动作"),
                    text(item, "dialogue", "dialog", "台词"),
                    text(item, "shot", "camera", "镜头")
            ));
        }
        return result;
    }

    private List<AssetDto> parseAssets(JsonNode node, String style) {
        List<AssetDto> result = new ArrayList<>();
        for (JsonNode item : itemsNode(node)) {
            String type = text(item, "type", "category");
            String name = text(item, "name", "title");
            String description = text(item, "description", "desc", "介绍");
            String imagePrompt = text(item, "imagePrompt", "prompt", "image_prompt");
            if (type != null && type.equalsIgnoreCase("character") && !looksLikeCharacterSheetPrompt(imagePrompt)) {
                imagePrompt = fallbackCharacterSheetPrompt(name, description, style);
            }
            imagePrompt = promptPipeline.normalizeAssetPersist(
                    ensureStyleKeyword(imagePrompt, style),
                    type == null ? "" : type.toLowerCase(), style);
            result.add(new AssetDto(type, name, description, imagePrompt));
        }
        return result;
    }

    private List<ShotDto> parseShots(JsonNode node) {
        List<ShotDto> result = new ArrayList<>();
        int fallbackIndex = 0;
        for (JsonNode item : itemsNode(node)) {
            List<ReferencePromptDto> refs = new ArrayList<>();
            JsonNode refsNode = item.get("referencePrompts");
            if (refsNode == null) {
                refsNode = item.get("references");
            }
            if (refsNode != null && refsNode.isArray()) {
                for (JsonNode ref : refsNode) {
                    refs.add(new ReferencePromptDto(
                            text(ref, "type"),
                            text(ref, "prompt", "imagePrompt"),
                            text(ref, "note", "description")
                    ));
                }
            }
            int scriptIndex = intValue(item, fallbackIndex, "scriptIndex", "index", "order");
            List<String> linked = stringList(item, "linkedAssetIds", "linkedAssets", "linkedAssetNames", "assets");
            result.add(new ShotDto(
                    scriptIndex,
                    text(item, "videoPrompt", "video_prompt", "prompt"),
                    refs,
                    linked
            ));
            fallbackIndex++;
        }
        return result;
    }

    private String text(JsonNode node, String... keys) {
        if (node == null) {
            return "";
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isTextual() || value.isNumber() || value.isBoolean()) {
                return value.asText().trim();
            }
            if (value.isArray()) {
                List<String> parts = new ArrayList<>();
                value.forEach(v -> {
                    String part = v.isTextual() || v.isNumber() ? v.asText() : v.toString();
                    if (!part.isBlank()) {
                        parts.add(part.trim());
                    }
                });
                return String.join("\n", parts);
            }
            if (value.isObject()) {
                return value.toString();
            }
        }
        return "";
    }

    private List<String> stringList(JsonNode node, String... keys) {
        List<String> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isArray()) {
                value.forEach(v -> {
                    String part = v.asText("").trim();
                    if (!part.isBlank()) {
                        result.add(part);
                    }
                });
                return result;
            }
            String text = value.asText("").trim();
            if (!text.isBlank()) {
                for (String part : text.split("[,，、/|]")) {
                    if (!part.isBlank()) {
                        result.add(part.trim());
                    }
                }
                return result;
            }
        }
        return result;
    }

    private int intValue(JsonNode node, int defaultValue, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isNumber()) {
                return value.asInt();
            }
            if (value != null && value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText().trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return defaultValue;
    }

    public record ScriptDto(String time, String location, List<String> characters,
                            String action, String dialogue, String shot) {}

    public record AssetDto(String type, String name, String description, String imagePrompt) {}

    public record ReferencePromptDto(String type, String prompt, String note) {}

    public record ShotDto(int scriptIndex, String videoPrompt, List<ReferencePromptDto> referencePrompts,
                          List<String> linkedAssets) {}

    public record AssistantAction(String type, String content) {}

    public record AssistantDecision(String reply, List<AssistantAction> actions) {}

    public enum ShotsClassifyMode {
        CHAT,
        GENERATE_ALL,
        GENERATE_SOME,
        REGENERATE_SOME
    }

    /** 分镜/参考图提示词语言；默认中文 */
    public enum PromptLanguage {
        ZH,
        EN;

        public boolean isChinese() {
            return this == ZH;
        }

        public String label() {
            return this == ZH ? "中文" : "英文";
        }
    }

    public record ShotsClassifyIntent(
            ShotsClassifyMode mode,
            List<Integer> shotNumbers,
            boolean includeLast,
            PromptLanguage promptLanguage
    ) {
        public static ShotsClassifyIntent chat() {
            return new ShotsClassifyIntent(ShotsClassifyMode.CHAT, List.of(), false, null);
        }

        public boolean needsGenerate() {
            return mode == ShotsClassifyMode.GENERATE_ALL
                    || mode == ShotsClassifyMode.GENERATE_SOME
                    || mode == ShotsClassifyMode.REGENERATE_SOME;
        }
    }

    public enum WriteSourceMode {
        CHAT,
        REWRITE_CHAPTER,
        APPEND_CHAPTERS,
        FILL_PREFIX,
        WRITE_NEW,
        DELETE_CHAPTER,
        ALREADY_COMPLETE
    }

    public record WriteSourceIntent(
            WriteSourceMode mode,
            Integer targetChapter,
            Integer startChapter,
            Integer endChapter,
            Integer chapterCount,
            Integer prefixTarget
    ) {
        public static WriteSourceIntent chat() {
            return new WriteSourceIntent(WriteSourceMode.CHAT, null, null, null, null, null);
        }

        public static WriteSourceIntent rewrite(int chapter) {
            return new WriteSourceIntent(WriteSourceMode.REWRITE_CHAPTER, chapter, null, null, null, null);
        }

        public static WriteSourceIntent append(int start, int end) {
            return new WriteSourceIntent(WriteSourceMode.APPEND_CHAPTERS, null, start, end, null, null);
        }

        public static WriteSourceIntent fillPrefix(int prefixTarget, int start, int end) {
            return new WriteSourceIntent(WriteSourceMode.FILL_PREFIX, null, start, end, null, prefixTarget);
        }

        public static WriteSourceIntent writeNew(int count) {
            return new WriteSourceIntent(WriteSourceMode.WRITE_NEW, null, null, null, count, null);
        }

        public static WriteSourceIntent delete(int chapter) {
            return new WriteSourceIntent(WriteSourceMode.DELETE_CHAPTER, chapter, null, null, null, null);
        }

        public static WriteSourceIntent alreadyComplete(int maxChapter, int prefixTarget) {
            return new WriteSourceIntent(WriteSourceMode.ALREADY_COMPLETE, null, null, null, null, prefixTarget);
        }

        public boolean needsSourceWrite() {
            return mode == WriteSourceMode.REWRITE_CHAPTER
                    || mode == WriteSourceMode.APPEND_CHAPTERS
                    || mode == WriteSourceMode.FILL_PREFIX
                    || mode == WriteSourceMode.WRITE_NEW
                    || mode == WriteSourceMode.DELETE_CHAPTER;
        }
    }

    private Integer nullableIntValue(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                String text = value.asText().trim();
                if (text.isBlank() || "null".equalsIgnoreCase(text)) {
                    continue;
                }
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private record ChapterPlan(int index, String title, String summary) {}
}
