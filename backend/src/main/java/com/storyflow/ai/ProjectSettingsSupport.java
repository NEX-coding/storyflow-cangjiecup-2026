package com.storyflow.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storyflow.entity.AssetType;
import com.storyflow.entity.Project;
import org.springframework.stereotype.Component;

/**
 * 读写 Project.settingsJson（元素风格前缀、参考图模式等）。
 */
@Component
public class ProjectSettingsSupport {

    public static final String MODE_FIRST_LAST = "first_last";
    public static final String MODE_SINGLE = "single";
    public static final String MODE_NONE = "none";
    /** @deprecated 视为 single */
    public static final String MODE_STORYBOARD = "storyboard";

    private final ObjectMapper objectMapper;

    public ProjectSettingsSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record ElementStyle(String prefix, String suffix) {
        public static ElementStyle empty() {
            return new ElementStyle("", "");
        }
    }

    public record Settings(
            ElementStyle character,
            ElementStyle scene,
            ElementStyle prop,
            String refPromptMode,
            String videoAspectRatio,
            String refImageAspectRatio,
            String assetImageAspectRatio,
            /** 「风格参考」文字描述；生图时前置，不改分镜原文 */
            String styleReferenceNote
    ) {
        public ElementStyle styleFor(AssetType type) {
            if (type == null) {
                return ElementStyle.empty();
            }
            return switch (type) {
                case character -> character != null ? character : ElementStyle.empty();
                case scene -> scene != null ? scene : ElementStyle.empty();
                case prop -> prop != null ? prop : ElementStyle.empty();
            };
        }

        public boolean storyboardMode() {
            String m = normalizeMode(refPromptMode);
            return MODE_SINGLE.equals(m) || MODE_STORYBOARD.equalsIgnoreCase(refPromptMode);
        }

        public String normalizedRefPromptMode() {
            return normalizeMode(refPromptMode);
        }

        public String normalizedVideoAspectRatio() {
            return AspectRatioSupport.normalizeVideoRatio(videoAspectRatio);
        }

        public String normalizedRefImageAspectRatio() {
            return AspectRatioSupport.normalizeImageRatio(refImageAspectRatio);
        }

        public String normalizedAssetImageAspectRatio() {
            return AspectRatioSupport.normalizeImageRatio(assetImageAspectRatio);
        }

        /** @deprecated 兼容旧字段；等同参考图比例 */
        public String normalizedImageAspectRatio() {
            return normalizedRefImageAspectRatio();
        }
    }

    public static String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return MODE_FIRST_LAST;
        }
        String m = raw.trim().toLowerCase();
        if (MODE_STORYBOARD.equals(m)) {
            return MODE_SINGLE;
        }
        if (MODE_FIRST_LAST.equals(m) || MODE_SINGLE.equals(m) || MODE_NONE.equals(m)) {
            return m;
        }
        return MODE_FIRST_LAST;
    }

    public Settings read(Project project) {
        return parse(project != null ? project.getSettingsJson() : null);
    }

    public Settings parse(String settingsJson) {
        ElementStyle character = defaultStyle(AssetType.character);
        ElementStyle scene = defaultStyle(AssetType.scene);
        ElementStyle prop = defaultStyle(AssetType.prop);
        String mode = MODE_FIRST_LAST;
        String videoRatio = AspectRatioSupport.RATIO_16_9;
        String refImageRatio = AspectRatioSupport.RATIO_1_1;
        String assetImageRatio = AspectRatioSupport.RATIO_1_1;
        String styleReferenceNote = "";
        if (settingsJson == null || settingsJson.isBlank()) {
            return new Settings(character, scene, prop, mode, videoRatio, refImageRatio, assetImageRatio, styleReferenceNote);
        }
        try {
            JsonNode root = objectMapper.readTree(settingsJson);
            JsonNode styles = root.path("elementStyles");
            character = readStyle(styles.path("character"), character);
            scene = readStyle(styles.path("scene"), scene);
            prop = readStyle(styles.path("prop"), prop);
            String m = root.path("refPromptMode").asText("");
            mode = normalizeMode(m);
            videoRatio = AspectRatioSupport.normalizeVideoRatio(root.path("videoAspectRatio").asText(""));
            // 旧版单字段 imageAspectRatio 同时作为参考图/资产图回退
            String legacy = root.path("imageAspectRatio").asText("");
            String refRaw = root.path("refImageAspectRatio").asText("");
            String assetRaw = root.path("assetImageAspectRatio").asText("");
            if (refRaw.isBlank()) {
                refRaw = legacy;
            }
            if (assetRaw.isBlank()) {
                assetRaw = legacy;
            }
            refImageRatio = AspectRatioSupport.normalizeImageRatio(refRaw);
            assetImageRatio = AspectRatioSupport.normalizeImageRatio(assetRaw);
            styleReferenceNote = root.path("styleReferenceNote").asText("");
        } catch (Exception ignored) {
            // keep defaults
        }
        return new Settings(character, scene, prop, mode, videoRatio, refImageRatio, assetImageRatio, styleReferenceNote);
    }

    public String mergeInto(String existingJson, Settings settings) {
        try {
            ObjectNode root = existingJson != null && !existingJson.isBlank()
                    ? (ObjectNode) objectMapper.readTree(existingJson)
                    : objectMapper.createObjectNode();
            ObjectNode styles = objectMapper.createObjectNode();
            styles.set("character", styleNode(settings.character()));
            styles.set("scene", styleNode(settings.scene()));
            styles.set("prop", styleNode(settings.prop()));
            root.set("elementStyles", styles);
            root.put("refPromptMode", normalizeMode(settings.refPromptMode()));
            root.put("videoAspectRatio", AspectRatioSupport.normalizeVideoRatio(settings.videoAspectRatio()));
            root.put("refImageAspectRatio",
                    AspectRatioSupport.normalizeImageRatio(settings.refImageAspectRatio()));
            root.put("assetImageAspectRatio",
                    AspectRatioSupport.normalizeImageRatio(settings.assetImageAspectRatio()));
            if (settings.styleReferenceNote() != null && !settings.styleReferenceNote().isBlank()) {
                root.put("styleReferenceNote", settings.styleReferenceNote().trim());
            } else {
                root.remove("styleReferenceNote");
            }
            root.remove("imageAspectRatio");
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return existingJson;
        }
    }

    public ElementStyle defaultStyle(AssetType type) {
        // 前缀留空表示「跟作品画风默认」；送 GPU 时由 PromptPrinciples.resolveElementStyle 补全
        String suffix = PromptPrinciples.assetQualitySuffix(type == null ? "" : type.name());
        return new ElementStyle("", suffix);
    }

    /** 送生图 API 前解析：用户自定义 > 风格包前缀。 */
    public ElementStyle effectiveStyleFor(AssetType type, StylePack pack, ElementStyle stored) {
        ElementStyle s = stored != null ? stored : defaultStyle(type);
        var resolved = PromptPrinciples.resolveElementStyle(
                s.prefix(), s.suffix(), type == null ? "" : type.name(), pack);
        return new ElementStyle(resolved.prefix(), resolved.suffix());
    }

    private ElementStyle readStyle(JsonNode node, ElementStyle fallback) {
        if (node == null || node.isMissingNode() || !node.isObject()) {
            return fallback;
        }
        String prefix = node.path("prefix").asText(fallback.prefix());
        String suffix = node.path("suffix").asText(fallback.suffix());
        return new ElementStyle(prefix, suffix);
    }

    private ObjectNode styleNode(ElementStyle style) {
        ObjectNode n = objectMapper.createObjectNode();
        ElementStyle s = style != null ? style : ElementStyle.empty();
        n.put("prefix", s.prefix() == null ? "" : s.prefix());
        n.put("suffix", s.suffix() == null ? "" : s.suffix());
        return n;
    }
}
