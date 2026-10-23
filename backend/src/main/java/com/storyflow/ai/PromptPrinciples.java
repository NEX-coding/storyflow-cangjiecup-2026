package com.storyflow.ai;

import com.storyflow.entity.Asset;
import com.storyflow.entity.AssetType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 提示词原则层：
 * <ul>
 *   <li>构图 / 画风 / 媒介 / 品质 分层，禁止硬编码与当前画风冲突的媒介词</li>
 *   <li>角色设定板专用词不得当作全片画风词写进分镜</li>
 *   <li>从角色 imagePrompt 提取共用画风词，确定性写回分镜（幂等）</li>
 * </ul>
 */
public final class PromptPrinciples {

    private static final String UNIFIED_STYLE_MARKER = "，统一画风：";

    /** 提取画风词时忽略：卫生词 + 设定板构图词 */
    private static final Set<String> STYLE_TOKEN_IGNORE = Set.of(
            "统一角色形象", "无文字无水印", "无文字", "无水印", "无边框", "单人",
            "全身", "半身", "特写", "三视图", "正面", "侧面", "背面", "角色",
            "角色设定参考图", "设定参考图", "设定表", "参考表", "横构图", "纯白背景",
            "柔和均匀光", "无文字标签", "A字站姿", "空手", "表情平静"
    );

    /** 含下列子串的 token 视为设定板专用，不得进入全片画风词 */
    private static final List<String> REFERENCE_SHEET_MARKERS = List.of(
            "站姿", "姿态", "姿势", "背景", "纯色", "三视图", "正面", "侧面",
            "背面", "全身", "半身", "构图", "比例", "排列", "视图", "头肩",
            "设定", "参考图", "横排", "等高"
    );

    private PromptPrinciples() {
    }

    // ── 品质收尾（通用卫生/结构，不含媒介） ─────────────────────────────

    public static String assetQualitySuffix(String type) {
        String t = type == null ? "" : type.trim().toLowerCase();
        return switch (t) {
            case "character" -> "无文字水印";
            case "scene" -> "空镜无人，禁止人物路人剪影，空间层次清晰，无文字水印";
            case "prop" -> "材质清晰，无人物，无文字水印";
            default -> "无文字水印";
        };
    }

    // ── 媒介规则（优先 StylePack.medium，其次风格名推断） ────────────────

    public static String inferMedium(String style) {
        return switch (PromptCatalog.normalizeStyleName(style)) {
            case "日漫赛璐璐", "韩漫清新" -> StylePack.MEDIUM_LINEART;
            case "国漫厚涂" -> StylePack.MEDIUM_PAINT;
            case "真人写实" -> StylePack.MEDIUM_PHOTO;
            case "电影胶片" -> StylePack.MEDIUM_FILM;
            case "3D CG动画" -> StylePack.MEDIUM_CG;
            case "古风水墨" -> StylePack.MEDIUM_INK;
            case "赛博朋克" -> StylePack.MEDIUM_CYBER;
            default -> StylePack.MEDIUM_LINEART;
        };
    }

    public static boolean allowsLineArtMedium(String style) {
        return StylePack.MEDIUM_LINEART.equals(inferMedium(style));
    }

    public static boolean allowsLineArtMedium(StylePack pack) {
        return pack != null && pack.allowsLineArt();
    }

    public static String stripConflictingMediumTerms(String prompt, String style) {
        return stripConflictingMediumTerms(prompt, null, style);
    }

    public static String stripConflictingMediumTerms(String prompt, StylePack pack) {
        return stripConflictingMediumTerms(prompt, pack, pack == null ? null : pack.id());
    }

    public static String stripConflictingMediumTerms(String prompt, StylePack pack, String styleFallback) {
        if (prompt == null || prompt.isBlank()) {
            return prompt;
        }
        String medium = pack != null ? pack.medium() : inferMedium(styleFallback);
        String cleaned = prompt;
        if (!StylePack.MEDIUM_LINEART.equals(medium)) {
            cleaned = cleaned
                    .replace("干净线稿", "")
                    .replace("干净线条", "")
                    .replace("闭合线稿", "")
                    .replace("流畅线稿", "")
                    .replace("二次元线稿", "");
        }
        if (!StylePack.MEDIUM_PHOTO.equals(medium) && !StylePack.MEDIUM_FILM.equals(medium)) {
            cleaned = cleaned
                    .replace("真实肤质摄影感", "")
                    .replace("单反摄影感", "")
                    .replace("写真呼吸感", "");
        }
        return normalizePunctuation(cleaned);
    }

    public static String normalizePunctuation(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text
                .replaceAll("[，,]{2,}", "，")
                .replaceAll("^[，,\\s]+|[，,\\s]+$", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    /** 去掉混风短语、冲突媒介词，并补全画风短名 */
    public static String ensureStyleKeyword(String prompt, String styleName) {
        if (prompt == null || prompt.isBlank()) {
            return prompt;
        }
        String name = styleName == null || styleName.isBlank() ? "" : styleName.trim();
        if (name.isEmpty()) {
            return prompt.trim();
        }
        String cleaned = prompt
                .replaceAll("国漫厚涂转|日漫赛璐璐转|韩漫清新转|古风水墨转|赛博朋克转|3D\\s*CG动画转|电影胶片转|真人写实转", "")
                .trim();
        cleaned = stripConflictingMediumTerms(cleaned, name);
        if (!cleaned.contains(name)) {
            cleaned = cleaned.replaceAll("[，,。\\s]+$", "") + "，" + name;
        }
        return cleaned;
    }

    // ── 角色设定板校验 / 噪声剥离 ───────────────────────────────────────

    public static boolean looksLikeCharacterSheetPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String p = prompt.replaceAll("\\s+", "");
        boolean sheetWord = p.contains("三视图") || p.contains("设定表") || p.contains("设定参考图")
                || p.contains("turnaround") || p.contains("参考图");
        boolean hasFaceAnchor = p.contains("正脸") || p.contains("头肩") || p.contains("面部特写") || p.contains("特写");
        boolean hasBodyViews = (p.contains("正面") || p.contains("front"))
                && (p.contains("侧面") || p.contains("side") || p.contains("背面") || p.contains("back"));
        boolean hasViews = sheetWord && (hasBodyViews || (hasFaceAnchor && (p.contains("全身") || hasBodyViews)));
        String qualityOnly = p.replaceAll("(顶级画质|最高品质|超精细细节|干净线条|柔和阴影|色彩鲜艳通透|专业插画|高清分辨率|完美人体比例|光影舒适|无畸形|无崩坏|不要出现文字|空白背景|人物三视图|角色设定图|角色设定参考图|，)+", "");
        boolean mostlyQuality = qualityOnly.length() < 12;
        return hasViews && !mostlyQuality;
    }

    /** 从外形摘要中去掉设定板构图噪声，保留可描述外形 */
    public static String stripReferenceSheetNoise(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return "";
        }
        t = t.replaceAll("(?i)(三视图|四视图|多视角|设定表|设定参考图|参考表|白底|正脸头肩特写|正脸特写|头肩特写|正面全身|侧面全身|背面全身|等高横排|横排|A字站姿|16:9|角色设定参考图)", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    /**
     * 首尾帧静帧：去掉视频运镜/时长词（开源 FLF 惯例：静帧不写 camera motion）。
     * 只剥短语级污染（如 video.md 的「运镜平稳」），不误伤「禁止运镜」否定句。
     */
    public static String stripVideoMotionForStill(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return "";
        }
        t = t.replaceAll("运镜(平稳|有力|流畅|缓慢|跟拍|推进|摇移|推拉|升降|环绕|固定)", "");
        t = t.replaceAll("(?i)camera\\s*(hold|push|pull|pan|tilt|dolly|track|orbit|zoom)", "");
        t = t.replaceAll("时长[：:]\\s*\\d+\\s*秒", "");
        t = t.replaceAll("(?i)\\b\\d{1,2}\\s*s\\b", "");
        t = t.replaceAll("环绕运镜|推镜靠近|拉镜远离|急推|急拉|微推|微拉", "");
        t = t.replaceAll("[，,]{2,}", "，");
        t = t.replaceAll("，\\s*，", "，");
        t = t.replaceAll("^[，,。\\s]+|[，,。\\s]+$", "");
        return t.replaceAll("\\s{2,}", " ").trim();
    }

    // ── 全片画风词提取（extractCommonStyleKeywords） ─────────────

    public static List<String> characterPromptsFromAssets(List<Asset> assets) {
        if (assets == null || assets.isEmpty()) {
            return List.of();
        }
        return assets.stream()
                .filter(a -> a.getType() == AssetType.character)
                .map(a -> {
                    String p = a.getImagePrompt();
                    if (p == null || p.isBlank()) {
                        p = a.getDescription();
                    }
                    return p == null ? "" : p.trim();
                })
                .filter(s -> !s.isBlank())
                .toList();
    }

    /**
     * 从多条角色 imagePrompt 提取全片共用画风词；单主角时取该角色尾部画风段。
     * 始终保证作品画风短名在结果中（若可解析）。
     */
    public static List<String> extractCommonStyleKeywords(List<String> characterPrompts, String stylePreset) {
        List<List<String>> tokenSets = new ArrayList<>();
        for (String prompt : characterPrompts) {
            if (prompt == null || prompt.isBlank()) {
                continue;
            }
            List<String> tokens = tokenizeStyleCandidates(prompt);
            if (!tokens.isEmpty()) {
                tokenSets.add(tokens);
            }
        }
        List<String> keywords = new ArrayList<>();
        if (tokenSets.size() >= 2) {
            Set<String> common = new LinkedHashSet<>(tokenSets.get(0));
            for (int i = 1; i < tokenSets.size(); i++) {
                common.retainAll(tokenSets.get(i));
            }
            for (String tok : tokenSets.get(0)) {
                if (common.contains(tok)) {
                    keywords.add(tok);
                }
            }
        } else if (tokenSets.size() == 1) {
            keywords.addAll(tokenSets.get(0));
        }
        keywords = keywords.stream().limit(12).collect(Collectors.toCollection(ArrayList::new));
        String styleName = stylePreset == null || stylePreset.isBlank()
                ? "" : PromptCatalog.normalizeStyleName(stylePreset);
        if (!styleName.isEmpty() && keywords.stream().noneMatch(styleName::equals)) {
            keywords.add(0, styleName);
        }
        return keywords.stream().distinct().limit(12).toList();
    }

    private static List<String> tokenizeStyleCandidates(String prompt) {
        List<String> tokens = new ArrayList<>();
        for (String raw : prompt.split("[，,；;\\n]")) {
            String tok = raw.trim();
            if (tok.isEmpty() || tok.length() > 14) {
                continue;
            }
            if (STYLE_TOKEN_IGNORE.contains(tok)) {
                continue;
            }
            if (isReferenceSheetPhrase(tok)) {
                continue;
            }
            tokens.add(tok);
        }
        return tokens;
    }

    private static boolean isReferenceSheetPhrase(String token) {
        for (String marker : REFERENCE_SHEET_MARKERS) {
            if (token.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** LLM 上下文：要求分镜沿用角色形象图画风词 */
    public static String buildCharacterStyleBlock(List<String> styleKeywords) {
        List<String> keywords = styleKeywords == null ? List.of() : styleKeywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .toList();
        if (keywords.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【全片统一画风 · 必须与角色形象图一致】\n");
        sb.append("角色形象图已锁定以下画风关键词（分镜 videoPrompt 与 referencePrompts.prompt 须原样包含，不得漏写或改写成近似词）：\n");
        for (String kw : keywords) {
            sb.append("- ").append(kw).append("\n");
        }
        sb.append("禁止把设定板专用词（三视图/站姿/纯色背景等）写进分镜；分镜只写场景动作与光影。");
        return sb.toString().trim();
    }

    /**
     * 确定性兜底：把缺失的全片画风词并入分镜提示词（幂等，先剥旧后缀再补）。
     * 结构化 videoPrompt（故事版/首尾帧/无参考六段式）只保留开头【风格参考】，禁止末尾「统一画风」小作文。
     */
    public static String enforceStyleKeywords(String prompt, List<String> styleKeywords, String stylePreset) {
        if (prompt == null || prompt.isBlank()) {
            return prompt;
        }
        List<String> keywords = styleKeywords == null ? List.of() : styleKeywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .toList();
        String stripped = stripLegacyUnifiedStyleSuffix(prompt);
        // 结构化视频提示词：风格只保留开头参考行，勿把角色设定板词贴到末尾
        if (isStructuredVideoPrompt(stripped)) {
            return stripConflictingMediumTerms(stripped, stylePreset);
        }
        String normalized = ensureStyleKeyword(
                stripConflictingMediumTerms(stripped, stylePreset),
                stylePreset);

        if (keywords.isEmpty()) {
            return normalized;
        }
        List<String> missing = keywords.stream()
                .filter(kw -> !normalized.contains(kw))
                .toList();
        if (missing.isEmpty()) {
            return normalized;
        }
        return normalized.replaceAll("[，,。\\s]+$", "")
                + UNIFIED_STYLE_MARKER
                + String.join("，", missing)
                + "。";
    }

    /** 单图模式 videoPrompt：有时间轴 +（风格参考或故事版绑定） */
    public static boolean isStoryboardDrivenVideoPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        boolean hasTimeline = prompt.contains("【故事版时间轴】") || prompt.contains("底部左起第");
        boolean hasBind = prompt.contains("【故事版绑定】") || prompt.contains("【风格参考】");
        return hasTimeline && hasBind;
    }

    /** 首尾帧 / 无参考 / 故事版等已分段的视频提示词 */
    public static boolean isStructuredVideoPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        if (isStoryboardDrivenVideoPrompt(prompt)) {
            return true;
        }
        if (prompt.contains("【首尾帧驱动】")) {
            return true;
        }
        // 无参考六段式
        return prompt.contains("【风格参考】")
                && prompt.contains("【一句话概述】")
                && (prompt.contains("【动作】") || prompt.contains("【过渡】"));
    }

    /**
     * 首尾帧 videoPrompt 清洗：按【标签】切分（容忍同行挤标签），去掉设定板污染，收成过渡结构。
     */
    public static String sanitizeFlfVideoPrompt(String prompt) {
        String text = prompt == null ? "" : prompt.trim();
        if (text.isEmpty()) {
            return text;
        }
        text = stripDramaStyleBlockForVideo(text);
        text = stripLegacyUnifiedStyleSuffix(text);
        text = text.replaceAll(
                "【(?:角色姓名|年龄|性别|身高|身份|时代/题材|时代|题材|核心气质|脸部特征|体型|主色调|服装设定|服装状态|手部姿态)】[：:][^\\n【]*",
                "");
        text = text.replaceAll(
                "(?:右侧|左侧)?约?\\d+/\\d+为?正脸[^，。\\n【]*|正脸与脖子特写|头肩特写|无环境无道具无水印无网格|无网格|纯色背景|设定板|三视图",
                "");
        // 同行多标签 → 先按【切开，再逐段归类
        text = text.replaceAll("(?=【)", "\n").replaceAll("\\n{2,}", "\n").trim();

        String styleRef = "";
        String overview = "";
        String transition = "";
        String lens = "";
        String voice = "";
        String flfHint = "";
        String[] durationHolder = {"6"};
        StringBuilder free = new StringBuilder();
        boolean sawStyleRef = false;

        for (String rawLine : text.split("\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String tag = "";
            String body = line;
            if (line.startsWith("【")) {
                int close = line.indexOf('】');
                if (close > 0) {
                    tag = line.substring(1, close).trim();
                    body = line.substring(close + 1).trim();
                }
            }
            body = stripInlineTags(pullDuration(body, durationHolder));

            if ("风格参考".equals(tag)) {
                if (!sawStyleRef) {
                    int cut = firstNarrativeBreak(body);
                    if (cut > 0 && cut < body.length()) {
                        styleRef = body.substring(0, cut).replaceAll("[，,。\\s]+$", "").trim();
                        String rest = body.substring(cut).trim();
                        if (!rest.isBlank()) {
                            free.append(rest).append(' ');
                        }
                    } else {
                        styleRef = clipChars(body, 80);
                    }
                    sawStyleRef = true;
                } else {
                    int cut = firstNarrativeBreak(body);
                    String rest = cut > 0 && cut < body.length() ? body.substring(cut).trim() : body;
                    String[] lensPull = pullLensPhrase(rest);
                    rest = lensPull[0];
                    if (!lensPull[1].isBlank() && lens.isBlank()) {
                        lens = lensPull[1];
                    }
                    if (!rest.isBlank()) {
                        free.append(rest).append(' ');
                    }
                }
                continue;
            }
            if ("首尾帧驱动".equals(tag)) {
                // 非模板句里常藏起幅/落幅动作，留下作概述线索
                if (!body.isBlank()
                        && !body.contains("以首帧为起点")
                        && !body.contains("只生成两帧之间")) {
                    flfHint = body
                            .replaceAll("以首帧", "")
                            .replaceAll("为起点[、,]?尾帧", "到")
                            .replaceAll("为终点[；;]?只生成中间连续动作[。.]?", "")
                            .replaceAll("只生成两帧之间连续动作[。.]?", "")
                            .trim();
                }
                continue;
            }
            if ("一句话概述".equals(tag)) {
                if (overview.isBlank() && !body.isBlank() && !looksLikeMetaDriver(body)) {
                    overview = body;
                } else if (!body.isBlank() && !looksLikeMetaDriver(body)) {
                    free.append(body).append(' ');
                }
                continue;
            }
            if ("过渡".equals(tag) || "动作".equals(tag)) {
                if (transition.isBlank() && !body.isBlank()) {
                    transition = body;
                } else if (!body.isBlank() && !transition.contains(clipChars(body, 12))) {
                    free.append(body).append(' ');
                }
                continue;
            }
            if ("镜头".equals(tag)) {
                if (!body.isBlank()) {
                    lens = body.replace('·', '，');
                }
                continue;
            }
            if ("声音".equals(tag)) {
                if (!body.isBlank()) {
                    // 保留台词原文；去掉「成片无烧录字幕」之类套话，重建时再补
                    voice = body.replaceAll("；?成片无烧录字幕。?", "").trim();
                }
                continue;
            }
            if ("一致".equals(tag) || "节奏".equals(tag)
                    || "约束".equals(tag) || "画面".equals(tag)
                    || "故事版绑定".equals(tag) || "故事版时间轴".equals(tag)) {
                continue;
            }
            // 无标签残留正文
            String[] lensPull = pullLensPhrase(body);
            body = lensPull[0];
            if (!lensPull[1].isBlank() && lens.isBlank()) {
                lens = lensPull[1];
            }
            if (!body.isBlank() && !looksLikeMetaDriver(body)) {
                free.append(body).append(' ');
            }
        }
        String duration = durationHolder[0];

        String freeText = stripInlineTags(free.toString()).replaceAll("\\s+", " ").trim()
                .replaceAll("[，,]{2,}", "，")
                .replaceAll("^[，,。\\s]+|[，,。\\s]+$", "")
                .trim();
        overview = stripInlineTags(overview);
        transition = stripInlineTags(transition);
        lens = stripInlineTags(lens);
        // 声音行允许保留「角色名：台词」冒号结构，只剥误嵌标签
        voice = voice.replaceAll("【[^】]{1,20}】", " ").replaceAll("\\s+", " ").trim();

        if (transition.isBlank() && !freeText.isBlank()) {
            transition = freeText;
        }
        if (overview.isBlank() || looksLikeMetaDriver(overview)) {
            if (!flfHint.isBlank()) {
                overview = clipChars(flfHint, 40);
            } else if (!transition.isBlank()) {
                overview = clipChars(transition
                        .replaceAll("^(先|从|随后|最后)", "")
                        .replaceAll("[，,].*$", ""), 40);
            }
        }
        if (transition.isBlank()) {
            transition = "先承接首帧姿态，随后完成关键动作，最后收束到尾帧落幅。";
        }
        if (!transition.matches(".*(?:先|从).*(?:随后|再|接着).*(?:最后|而后).*")
                && !transition.contains("先") && !transition.contains("随后") && !transition.contains("最后")) {
            transition = "先承接首帧，随后" + clipChars(transition, 60) + "，最后收束到尾帧。";
        }
        // 「从A，随后B，最后C」也算合格，不必强行再包一层
        if (lens.isBlank()) {
            lens = "中景，固定镜头";
        }
        if (!"6".equals(duration) && !"10".equals(duration)) {
            duration = "6";
        }

        List<String> lines = new ArrayList<>();
        if (!styleRef.isBlank()) {
            lines.add("【风格参考】" + styleRef);
        }
        lines.add("【首尾帧驱动】以首帧为起点、尾帧为终点；只生成两帧之间连续动作。");
        if (!overview.isBlank()) {
            lines.add("【一句话概述】" + clipChars(overview, 40));
        }
        lines.add("【过渡】" + clipChars(transition, 120));
        lines.add("【镜头】" + clipChars(lens, 40));
        if (!voice.isBlank()) {
            lines.add("【声音】" + clipChars(voice, 160) + "；成片无烧录字幕。");
        }
        lines.add("【一致】同一人设服装场景；不换脸；成片无烧录字幕。");
        lines.add("【节奏】时长：" + duration + "秒；台词时段镜头稳定不推拉。");
        return String.join("\n", lines);
    }

    /** 去掉字段值里误嵌的【标签】 */
    private static String stripInlineTags(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("【[^】]{1,20}】", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("[，,]{2,}", "，")
                .replaceAll("^[，,。\\s]+|[，,。\\s]+$", "")
                .trim();
    }

    /** 元驱动套话 / 空壳，不能当概述 */
    private static boolean looksLikeMetaDriver(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String t = text.trim();
        if (t.contains("【") || t.contains("】")) {
            return true;
        }
        return t.contains("只生成中间连续动作")
                || t.contains("只生成两帧之间")
                || t.contains("以首帧为起点、尾帧为终点");
    }

    /** 画风短句与剧情正文的分界：句号/「。人物名」等 */
    private static int firstNarrativeBreak(String body) {
        if (body == null || body.isBlank()) {
            return -1;
        }
        // 短画风句后接剧情：在第一个句号处分，且后文够长
        int dot = body.indexOf('。');
        if (dot >= 4 && dot <= 40 && body.length() - dot > 16) {
            return dot + 1;
        }
        // 「风格词。姓名在…」
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^[\\u4e00-\\u9fffA-Za-z0-9、，,]{2,36}[。．\\.\\s]+")
                .matcher(body);
        if (m.find() && body.length() - m.end() > 16) {
            return m.end();
        }
        return -1;
    }

    /** 抽出「时长：X秒」写入 holder，返回去掉时长后的文本 */
    private static String pullDuration(String text, String[] durationHolder) {
        if (text == null || text.isBlank()) {
            return "";
        }
        java.util.regex.Matcher dm = java.util.regex.Pattern
                .compile("时长[：:]\\s*(\\d{1,2})\\s*秒")
                .matcher(text);
        if (dm.find()) {
            String sec = dm.group(1);
            if ("6".equals(sec) || "10".equals(sec)) {
                durationHolder[0] = sec;
            }
            text = (text.substring(0, dm.start()) + text.substring(dm.end())).trim();
        }
        return text.replaceAll("[，,]{2,}", "，").replaceAll("^[，,。\\s]+|[，,。\\s]+$", "").trim();
    }

    /** 从叙事里抽出「镜头…」短语 → [剩余正文, 镜头句] */
    private static String[] pullLensPhrase(String text) {
        if (text == null || text.isBlank()) {
            return new String[]{"", ""};
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("镜头([^，。\\n]{2,24})")
                .matcher(text);
        if (m.find()) {
            String lens = "镜头" + m.group(1).trim();
            String rest = (text.substring(0, m.start()) + text.substring(m.end())).trim()
                    .replaceAll("[，,]{2,}", "，")
                    .replaceAll("^[，,。\\s]+|[，,。\\s]+$", "")
                    .trim();
            return new String[]{rest, lens};
        }
        return new String[]{text, ""};
    }

    private static String clipChars(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max).replaceAll("[，,。\\s]+$", "") + "…";
    }

    /**
     * 无参考 / 通用视频提示词：去掉误贴的【画面风格｜…】长段，保留【风格参考】一行即可。
     */
    public static String stripDramaStyleBlockForVideo(String prompt) {
        String text = prompt == null ? "" : prompt.trim();
        if (text.isEmpty() || !text.contains("【画面风格")) {
            return text;
        }
        // 整段「【画面风格｜xxx】…」直到下一【标签】或文末
        return text.replaceAll("(?m)^【画面风格[^】]*】[^\\n]*(?:\\n(?!【)[^\\n]*)*", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static String stripLegacyUnifiedStyleSuffix(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        // 「，统一画风：」或行内「统一画风：」后的设定板小作文一律去掉
        String text = prompt.replaceAll("(?:,|，)?\\s*统一画风[：:][^\\n]*", "");
        int idx = text.indexOf(UNIFIED_STYLE_MARKER);
        if (idx >= 0) {
            text = text.substring(0, idx);
        }
        return text.replaceAll("[，,。\\s]+$", "").trim();
    }

    // ── 元素前后缀（「角色/场景/物品风格」：空=跟画风默认） ─────────

    public record ResolvedElementStyle(String prefix, String suffix) {
    }

    /**
     * 用户未填 prefix/suffix 时用风格包前缀；无包时 prefix 为空（调用方应先 {@link PromptCatalog#stylePack}）。
     */
    public static ResolvedElementStyle resolveElementStyle(
            String storedPrefix,
            String storedSuffix,
            String assetType,
            StylePack pack) {
        String typeKey = assetType == null ? "" : assetType.trim().toLowerCase();
        String styleId = pack != null ? pack.id() : PromptCatalog.DEFAULT_STYLE;
        String packPrefix = pack != null ? pack.prefixFor(typeKey) : "";
        String prefix = storedPrefix == null || storedPrefix.isBlank()
                ? packPrefix
                : storedPrefix.trim();
        String rawSuffix = storedSuffix == null || storedSuffix.isBlank()
                ? assetQualitySuffix(typeKey)
                : storedSuffix.trim();
        String suffix = stripConflictingMediumTerms(rawSuffix, pack, styleId);
        if (suffix.isBlank()) {
            suffix = assetQualitySuffix(typeKey);
        }
        return new ResolvedElementStyle(prefix, suffix);
    }

    /** 作品级风格参考描述（有则 prepend） */
    public static String prependStyleReference(String body, String styleReferenceNote) {
        String note = styleReferenceNote == null ? "" : styleReferenceNote.trim();
        String text = body == null ? "" : body.trim();
        if (note.isEmpty()) {
            return text;
        }
        if (text.isEmpty()) {
            return "【风格参考】" + note;
        }
        if (text.contains(note)) {
            return text;
        }
        // 已有【风格参考】行：替换为首行官方画风短句，避免双风格行
        if (text.startsWith("【风格参考】") || text.contains("\n【风格参考】")) {
            String without = text.replaceAll("(?m)^【风格参考】[^\\n]*\\n?", "").trim();
            return "【风格参考】" + note + (without.isEmpty() ? "" : "\n" + without);
        }
        return "【风格参考】" + note + "\n" + text;
    }

    /**
     * 资产落库规范化（媒介剥离 + 画风短名 + 品质后缀）。
     * GPU 包装请走 {@link PromptPipeline}；此处保留静态入口供无 Spring 场景。
     */
    public static String normalizeAssetImagePrompt(String composed, String assetType, String stylePreset) {
        if (composed == null || composed.isBlank()) {
            return composed;
        }
        String normalized = stripConflictingMediumTerms(composed, stylePreset);
        normalized = ensureStyleKeyword(normalized, stylePreset);
        String suffix = assetQualitySuffix(assetType);
        if (!suffix.isBlank() && !normalized.contains(suffix)) {
            normalized = normalized.replaceAll("[，,。\\s]+$", "") + "，" + suffix;
        }
        return stripConflictingMediumTerms(normalized, stylePreset);
    }

    public static String normalizeAssetImagePrompt(String composed, String assetType, StylePack pack) {
        if (composed == null || composed.isBlank()) {
            return composed;
        }
        StylePack p = pack;
        String styleId = p != null ? p.id() : PromptCatalog.DEFAULT_STYLE;
        String normalized = stripConflictingMediumTerms(composed, p, styleId);
        normalized = ensureStyleKeyword(normalized, styleId);
        String suffix = assetQualitySuffix(assetType);
        if (!suffix.isBlank() && !normalized.contains(suffix)) {
            normalized = normalized.replaceAll("[，,。\\s]+$", "") + "，" + suffix;
        }
        return stripConflictingMediumTerms(normalized, p, styleId);
    }
}
