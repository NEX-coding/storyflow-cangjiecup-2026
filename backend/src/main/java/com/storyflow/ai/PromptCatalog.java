package com.storyflow.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyflow.entity.PromptTemplate;
import com.storyflow.repository.PromptTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词目录（StoryFlow 官方提示词模板与风格包）：
 * <ul>
 *   <li>{@code prompts/system|skill/*.md}</li>
 *   <li>{@code prompts/style/{名}.md} → 画风总览</li>
 *   <li>{@code prompts/style/{名}/{character|scene|prop|video|negative|sheet}.md + meta.json}</li>
 * </ul>
 * 运行时：DB 优先 → classpath → 极简兜底。长中文不进业务 Java。
 */
@Service
public class PromptCatalog {

    private static final Logger log = LoggerFactory.getLogger(PromptCatalog.class);
    private static final ObjectMapper META_JSON = new ObjectMapper();

    public static final String STYLE_PREFIX = "style.";
    public static final String SKILL_PREFIX = "skill.";
    public static final String SKILL_CAMERA = "camera_lexicon";
    public static final String SKILL_ASSET_GUIDE = "asset_image_guide";
    public static final String SKILL_STYLE_CONSISTENCY = "style_consistency";
    public static final String SKILL_STORYBOARD_PRODUCTION = "storyboard_production";
    public static final String SKILL_FIRST_LAST_FRAME = "first_last_frame_production";
    public static final String DEFAULT_STYLE = "日漫赛璐璐";

    private final PromptTemplateRepository templateRepository;
    private final Map<String, String> classpathCache = new ConcurrentHashMap<>();

    public PromptCatalog(PromptTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
        reloadClasspath();
    }

    public String system(String category) {
        return resolve(category);
    }

    /** 作品画风总览句 */
    public String style(String styleName) {
        return resolve(STYLE_PREFIX + normalizeStyleName(styleName)).trim();
    }

    public String skill(String skillId) {
        return resolve(SKILL_PREFIX + skillId).trim();
    }

    public String cameraLexicon() {
        return skill(SKILL_CAMERA);
    }

    public String assetImageGuide() {
        return skill(SKILL_ASSET_GUIDE);
    }

    public String styleConsistencyGuide() {
        return skill(SKILL_STYLE_CONSISTENCY);
    }

    public String storyboardProductionGuide() {
        return skill(SKILL_STORYBOARD_PRODUCTION);
    }

    public String firstLastFrameProductionGuide() {
        return skill(SKILL_FIRST_LAST_FRAME);
    }

    /**
     * 组装风格包：总览 + 角色/场景/道具/视频前置 + 设定板骨架 + meta。
     * 缺文件时用内置短默认，保证旧项目仍可用。
     */
    public StylePack stylePack(String styleName) {
        String id = normalizeStyleName(styleName);
        String overview = style(id);
        String character = part(id, "character");
        String scene = part(id, "scene");
        String prop = part(id, "prop");
        String video = part(id, "video");
        String negative = part(id, "negative");
        String sheet = part(id, "sheet");

        String medium = StylePack.MEDIUM_LINEART;
        String sheetLayout = StylePack.LAYOUT_ANIME;
        String metaRaw = part(id, "meta");
        if (!metaRaw.isBlank()) {
            try {
                JsonNode meta = META_JSON.readTree(metaRaw);
                String m = meta.path("medium").asText("").trim();
                String layout = meta.path("sheetLayout").asText("").trim();
                if (!m.isEmpty()) {
                    medium = m;
                }
                if (!layout.isEmpty()) {
                    sheetLayout = layout;
                }
            } catch (Exception ignored) {
                // keep inferred defaults
            }
        } else {
            medium = PromptPrinciples.inferMedium(id);
            sheetLayout = switch (medium) {
                case StylePack.MEDIUM_PHOTO, StylePack.MEDIUM_FILM -> StylePack.LAYOUT_PHOTO;
                case StylePack.MEDIUM_CG, StylePack.MEDIUM_CYBER -> StylePack.LAYOUT_CG;
                default -> StylePack.LAYOUT_ANIME;
            };
        }

        // 包部件缺失时只挂画风短名，不在 Java 写散文默认（真相在 prompts/style/{名}/*.md）
        if (character.isBlank()) {
            character = id;
        }
        if (scene.isBlank()) {
            scene = id;
        }
        if (prop.isBlank()) {
            prop = id;
        }
        if (sheet.isBlank()) {
            sheet = switch (sheetLayout) {
                case StylePack.LAYOUT_PHOTO -> StylePack.defaultPhotoSheet();
                case StylePack.LAYOUT_CG -> StylePack.defaultCgSheet();
                default -> StylePack.defaultAnimeSheet();
            };
        }
        return new StylePack(id, medium, sheetLayout, overview, character, scene, prop, video, negative, sheet);
    }

    private String part(String styleId, String part) {
        return resolveOptional(STYLE_PREFIX + styleId + "." + part);
    }

    /** 模型输出不合格时的人物设定板兜底（骨架来自风格包 sheet.md） */
    public String fallbackCharacterSheet(String name, String description, String style) {
        StylePack pack = stylePack(style);
        String look = description == null || description.isBlank() ? "外形与服装待补充" : description.trim();
        return PromptPrinciples.ensureStyleKeyword(
                PromptPrinciples.stripConflictingMediumTerms(pack.renderSheet(name, look), pack),
                pack.id());
    }

    public int syncFromClasspath() {
        reloadClasspath();
        int n = 0;
        for (Map.Entry<String, String> e : classpathCache.entrySet()) {
            if (upsert(e.getKey(), displayName(e.getKey()), e.getValue())) {
                n++;
            }
        }
        log.info("PromptCatalog synced {} templates from classpath", n);
        return n;
    }

    public Map<String, String> classpathSnapshot() {
        return Map.copyOf(classpathCache);
    }

    public static String normalizeStyleName(String style) {
        if (style == null || style.isBlank()) {
            return DEFAULT_STYLE;
        }
        String name = style.trim();
        if ("3D CG 动画".equals(name)) {
            return "3D CG动画";
        }
        return name;
    }

    private String resolve(String category) {
        Optional<PromptTemplate> db = templateRepository.findFirstByCategoryAndActiveTrueOrderByVersionDesc(category);
        if (db.isPresent() && db.get().getContent() != null && !db.get().getContent().isBlank()) {
            return db.get().getContent();
        }
        String fromCp = classpathCache.get(category);
        if (fromCp != null && !fromCp.isBlank()) {
            return fromCp;
        }
        return fallback(category);
    }

    /** 风格包部件：缺省返回空串，不走散文 fallback */
    private String resolveOptional(String category) {
        Optional<PromptTemplate> db = templateRepository.findFirstByCategoryAndActiveTrueOrderByVersionDesc(category);
        if (db.isPresent() && db.get().getContent() != null && !db.get().getContent().isBlank()) {
            return db.get().getContent().trim();
        }
        String fromCp = classpathCache.get(category);
        return fromCp == null ? "" : fromCp.trim();
    }

    private boolean upsert(String category, String name, String content) {
        var existing = templateRepository.findFirstByCategoryAndActiveTrueOrderByVersionDesc(category);
        if (existing.isEmpty()) {
            PromptTemplate t = new PromptTemplate();
            t.setCategory(category);
            t.setName(name);
            t.setContent(content);
            t.setActive(true);
            t.setVersion(1);
            templateRepository.save(t);
            return true;
        }
        PromptTemplate t = existing.get();
        String old = t.getContent() == null ? "" : t.getContent().trim();
        if (old.equals(content.trim()) && name.equals(t.getName())) {
            return false;
        }
        t.setName(name);
        t.setContent(content);
        templateRepository.save(t);
        return true;
    }

    private void reloadClasspath() {
        classpathCache.clear();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            // 扁平：system|style|skill/*.md
            loadFlat(resolver);
            // 风格包：style/{名}/*.{md,json}
            loadStylePacks(resolver);
        } catch (Exception ex) {
            log.warn("Failed to load classpath prompts: {}", ex.getMessage());
        }
    }

    private void loadFlat(PathMatchingResourcePatternResolver resolver) throws Exception {
        Resource[] resources = resolver.getResources("classpath*:prompts/{system,style,skill}/*.md");
        for (Resource resource : resources) {
            if (resource == null || !resource.exists() || resource.getFilename() == null) {
                continue;
            }
            String filename = resource.getFilename();
            if ("README.md".equalsIgnoreCase(filename)) {
                continue;
            }
            String folder = parentFolderName(resource);
            if (folder == null) {
                continue;
            }
            String stem = filename.replaceFirst("\\.md$", "");
            String category = switch (folder) {
                case "system" -> stem;
                case "style" -> STYLE_PREFIX + stem;
                case "skill" -> SKILL_PREFIX + stem;
                default -> null;
            };
            if (category == null) {
                continue;
            }
            putClasspath(category, resource);
        }
    }

    private void loadStylePacks(PathMatchingResourcePatternResolver resolver) throws Exception {
        Resource[] resources = resolver.getResources("classpath*:prompts/style/*/*");
        for (Resource resource : resources) {
            if (resource == null || !resource.exists() || resource.getFilename() == null) {
                continue;
            }
            String filename = resource.getFilename();
            String styleName = stylePackNameFromUrl(resource);
            if (styleName == null || styleName.isBlank()) {
                continue;
            }
            String part;
            if (filename.endsWith(".md")) {
                part = filename.replaceFirst("\\.md$", "");
            } else if ("meta.json".equalsIgnoreCase(filename)) {
                part = "meta";
            } else {
                continue;
            }
            if (!isPackPart(part)) {
                continue;
            }
            putClasspath(STYLE_PREFIX + styleName + "." + part, resource);
        }
    }

    private static boolean isPackPart(String part) {
        return switch (part) {
            case "meta", "character", "scene", "prop", "video", "negative", "sheet" -> true;
            default -> false;
        };
    }

    private void putClasspath(String category, Resource resource) throws Exception {
        String text = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8).trim();
        if (!text.isEmpty()) {
            classpathCache.put(category, text);
        }
    }

    /** .../prompts/style/{styleName}/{file} → styleName（URL 百分号解码，支持中文目录名） */
    private static String stylePackNameFromUrl(Resource resource) throws Exception {
        String url = decodeResourcePath(resource);
        int fileIdx = url.lastIndexOf('/');
        int nameIdx = url.lastIndexOf('/', fileIdx - 1);
        int styleIdx = url.lastIndexOf('/', nameIdx - 1);
        if (styleIdx < 0 || nameIdx < 0 || fileIdx < 0) {
            return null;
        }
        if (!"style".equals(url.substring(styleIdx + 1, nameIdx))) {
            return null;
        }
        return url.substring(nameIdx + 1, fileIdx);
    }

    private static String parentFolderName(Resource resource) throws Exception {
        String url = decodeResourcePath(resource);
        int fileIdx = url.lastIndexOf('/');
        if (fileIdx <= 0) {
            return null;
        }
        int folderIdx = url.lastIndexOf('/', fileIdx - 1);
        if (folderIdx < 0) {
            return null;
        }
        return url.substring(folderIdx + 1, fileIdx);
    }

    private static String decodeResourcePath(Resource resource) throws Exception {
        String raw = resource.getURL().toString().replace('\\', '/');
        // file:/.../%E7%9C%9F%E4%BA%BA%E5%86%99%E5%AE%9E/character.md → 解码路径段
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return raw;
        }
    }

    private static String displayName(String category) {
        if (category.startsWith(STYLE_PREFIX)) {
            String rest = category.substring(STYLE_PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot > 0) {
                return "风格包｜" + rest.substring(0, dot) + "｜" + rest.substring(dot + 1);
            }
            return "画风｜" + rest;
        }
        if (category.startsWith(SKILL_PREFIX)) {
            return "技能｜" + category.substring(SKILL_PREFIX.length());
        }
        return switch (category) {
            case "script_generate" -> "剧本生成";
            case "asset_extract" -> "资产提取（重写/兜底）";
            case "asset_extract_character" -> "角色提取";
            case "asset_extract_scene" -> "场景提取";
            case "asset_extract_prop" -> "道具提取";
            case "shot_generate" -> "分镜生成";
            case "assistant" -> "AI助手";
            default -> category;
        };
    }

    private static String fallback(String category) {
        if (category.startsWith(STYLE_PREFIX)) {
            String name = category.substring(STYLE_PREFIX.length());
            if (name.contains(".")) {
                return "";
            }
            return "画面风格：" + name + "。严格按该画风绘制，禁止擅自换成其他画风。";
        }
        if ((SKILL_PREFIX + SKILL_CAMERA).equals(category)) {
            return "【镜头】景别 + 运镜（推/拉/摇/移/跟/环绕/固定）；台词时段优先固定。";
        }
        if ((SKILL_PREFIX + SKILL_ASSET_GUIDE).equals(category)) {
            return "人物 imagePrompt：16:9 左正脸特写 + 右正侧背全身；身份锚点+造型五槽；纯白背景无文字。";
        }
        return switch (category) {
            case "script_generate" -> "你是专业编剧，将小说转为结构化剧本，输出合法 JSON。";
            case "asset_extract" -> "你是美术指导，从剧本提取资产并编写中文生图提示词，输出合法 JSON。";
            case "asset_extract_character" -> "你是角色提取助手，输出人物 JSON 数组（name/aliases/description）。";
            case "asset_extract_scene" -> "你是场景提取助手，输出场景 JSON 数组（name/aliases/description）。";
            case "asset_extract_prop" -> "你是道具提取助手。只提剧情关键道具，宁缺毋滥（优先0–5，最多8），排除日常消耗品/服装/场景陈设；输出 JSON 数组（name/aliases/description）。";
            case "shot_generate" -> "你是短剧分镜架构师，划分镜头并编写中文图片/视频提示词，输出合法 JSON。";
            case "storyboard_production" -> "你是动画/影视分镜导演，按制作预演板规约编写故事版提示词。";
            case "assistant" -> "你是 StoryFlow 创作助手，帮助用户修改剧本、资产和提示词。";
            default -> "你是 StoryFlow 助手，输出合法 JSON。";
        };
    }

    public Map<String, String> listClasspathCategories() {
        return new LinkedHashMap<>(classpathCache);
    }
}
