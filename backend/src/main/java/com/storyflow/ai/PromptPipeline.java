package com.storyflow.ai;

import com.storyflow.entity.Asset;
import com.storyflow.entity.AssetType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 提示词唯一规范化管道。所有送模型 / 落库前的风格收尾只走这里。
 * <pre>
 *  body prep（设定板/构图头）→ 元素前后缀 → strip 媒介冲突
 *  → 画风短名 / 全片画风词 → 品质后缀 → 视频前置 → 用户风格参考 → 排除项
 * </pre>
 */
@Component
public class PromptPipeline {

    public enum Target {
        /** 资产 imagePrompt 落库：媒介 + 画风短名 + 品质后缀 */
        ASSET_PERSIST,
        /** 资产生图送 GPU：落库规范 + 元素前后缀 + 用户参考 + negative */
        ASSET_GPU,
        /** 分镜/参考图落库：全片画风词 + strip + 风格包视频前置 */
        SHOT_PERSIST,
        /** 分镜参考图送 GPU：可选画面风格行 + SHOT_PERSIST + 用户参考 */
        SHOT_GPU,
        /** 视频送 GPU：同 SHOT_GPU */
        VIDEO_GPU
    }

    public record Context(
            String stylePreset,
            AssetType assetType,
            ProjectSettingsSupport.ElementStyle elementStyle,
            List<Asset> linkedAssets,
            List<String> characterPromptHints,
            String styleReferenceNote,
            boolean applyDramaStyleLine,
            boolean applyVideoPrefix,
            boolean applyNegative,
            boolean applyAffixes,
            boolean applySheetFallback,
            /** 首尾帧静帧：videoPrefix 去运镜词，正文再剥一次 */
            boolean stillFrame
    ) {
        public static Context assetPersist(String stylePreset, AssetType assetType) {
            return new Context(stylePreset, assetType, null, null, null, null,
                    false, false, false, false, false, false);
        }

        public static Context assetGpu(
                String stylePreset,
                AssetType assetType,
                ProjectSettingsSupport.ElementStyle elementStyle,
                String styleReferenceNote) {
            return new Context(stylePreset, assetType, elementStyle, null, null, styleReferenceNote,
                    false, false, true, true, true, false);
        }

        public static Context shotPersist(String stylePreset, List<Asset> linkedAssets) {
            return new Context(stylePreset, null, null, linkedAssets, null, null,
                    false, true, false, false, false, false);
        }

        /** 首尾帧静帧落库：保留画质前置，去掉运镜/时长 */
        public static Context shotPersistStill(String stylePreset, List<Asset> linkedAssets) {
            return new Context(stylePreset, null, null, linkedAssets, null, null,
                    false, true, false, false, false, true);
        }

        /** 从角色 imagePrompt 文本列表构建（LLM 分镜落库，无 Asset 实体时） */
        public static Context shotPersistFromPrompts(String stylePreset, List<String> characterPromptHints) {
            return new Context(stylePreset, null, null, null, characterPromptHints, null,
                    false, true, false, false, false, false);
        }

        public static Context shotPersistStillFromPrompts(String stylePreset, List<String> characterPromptHints) {
            return new Context(stylePreset, null, null, null, characterPromptHints, null,
                    false, true, false, false, false, true);
        }

        public static Context shotGpu(
                String stylePreset,
                List<Asset> linkedAssets,
                String styleReferenceNote,
                boolean applyDramaStyleLine) {
            return new Context(stylePreset, null, null, linkedAssets, null, styleReferenceNote,
                    applyDramaStyleLine, true, false, false, false, false);
        }

        public static Context shotGpuStill(
                String stylePreset,
                List<Asset> linkedAssets,
                String styleReferenceNote) {
            return new Context(stylePreset, null, null, linkedAssets, null, styleReferenceNote,
                    true, true, false, false, false, true);
        }

        public static Context videoGpu(
                String stylePreset,
                List<Asset> linkedAssets,
                String styleReferenceNote) {
            return shotGpu(stylePreset, linkedAssets, styleReferenceNote, true);
        }
    }

    private final PromptCatalog promptCatalog;

    public PromptPipeline(PromptCatalog promptCatalog) {
        this.promptCatalog = promptCatalog;
    }

    public String finalize(Target target, String body, Context ctx) {
        if (body == null || body.isBlank()) {
            return body;
        }
        if (ctx == null) {
            return body.trim();
        }
        return switch (target) {
            case ASSET_PERSIST -> finalizeAssetPersist(body.trim(), ctx);
            case ASSET_GPU -> finalizeAssetGpu(body.trim(), ctx);
            case SHOT_PERSIST -> finalizeShot(body.trim(), ctx, false);
            case SHOT_GPU -> finalizeShot(body.trim(), ctx, true);
            case VIDEO_GPU -> appendVideoSpeakStability(finalizeShot(body.trim(), ctx, true));
        };
    }

    /**
     * 本地 H3 无音频驱动口型时，靠提示词稳住身份与说话嘴型，避免乱张嘴/换脸。
     */
    private static String appendVideoSpeakStability(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        if (text.contains("仅说话角色自然张嘴") || text.contains("不换脸不变形")) {
            return text;
        }
        String rule = "人物五官发型服饰与参考首帧一致，不换脸不变形；画面清晰锐利；"
                + "仅说话角色自然张嘴，口型跟语气，未说话角色闭嘴；"
                + "禁止面部融化、多人乱张嘴、剧烈抖动。";
        return text.replaceAll("[，,。\\s]+$", "") + "。" + rule;
    }

    /** 资产落库规范（无前后缀 / 用户参考 / negative） */
    public String normalizeAssetPersist(String composed, AssetType type, String stylePreset) {
        return finalize(Target.ASSET_PERSIST, composed, Context.assetPersist(stylePreset, type));
    }

    public String normalizeAssetPersist(String composed, String typeKey, String stylePreset) {
        return normalizeAssetPersist(composed, parseType(typeKey), stylePreset);
    }

    private String finalizeAssetPersist(String body, Context ctx) {
        StylePack pack = promptCatalog.stylePack(ctx.stylePreset());
        return PromptPrinciples.normalizeAssetImagePrompt(body, typeKey(ctx.assetType()), pack);
    }

    private String finalizeAssetGpu(String body, Context ctx) {
        StylePack pack = promptCatalog.stylePack(ctx.stylePreset());
        String typeKey = typeKey(ctx.assetType());
        String composed = body;
        if (ctx.applySheetFallback()
                && "character".equalsIgnoreCase(typeKey)
                && !PromptPrinciples.looksLikeCharacterSheetPrompt(composed)) {
            composed = promptCatalog.fallbackCharacterSheet("", composed, pack.id());
        }
        if (ctx.applyAffixes()) {
            String storedPrefix = ctx.elementStyle() != null ? ctx.elementStyle().prefix() : "";
            String storedSuffix = ctx.elementStyle() != null ? ctx.elementStyle().suffix() : "";
            PromptPrinciples.ResolvedElementStyle resolved = PromptPrinciples.resolveElementStyle(
                    storedPrefix, storedSuffix, typeKey, pack);
            StringBuilder joined = new StringBuilder();
            if (!resolved.prefix().isBlank() && !composed.contains(resolved.prefix())) {
                joined.append(resolved.prefix()).append("，");
            }
            joined.append(composed);
            if (!resolved.suffix().isEmpty() && !composed.contains(resolved.suffix())) {
                joined.append("，").append(resolved.suffix());
            }
            composed = joined.toString();
        }
        composed = finalizeAssetPersist(composed, ctx);
        composed = PromptPrinciples.prependStyleReference(composed, ctx.styleReferenceNote());
        StringBuilder neg = new StringBuilder();
        if ("scene".equalsIgnoreCase(typeKey) || "prop".equalsIgnoreCase(typeKey)) {
            neg.append("人物，路人，人脸，剪影，人群，半身人像");
        }
        if (ctx.applyNegative()
                && pack.negative() != null
                && !pack.negative().isBlank()) {
            if (neg.length() > 0) {
                neg.append("，");
            }
            neg.append(pack.negative());
        }
        if (neg.length() > 0 && !composed.contains("排除：")) {
            composed = composed.replaceAll("[，,。\\s]+$", "") + "。排除：" + neg;
        } else if (neg.length() > 0 && composed.contains("排除：")
                && ("scene".equalsIgnoreCase(typeKey) || "prop".equalsIgnoreCase(typeKey))
                && !composed.contains("路人")) {
            composed = composed.replaceAll("[，,。\\s]+$", "") + "，人物，路人，人脸，剪影，人群";
        }
        return composed;
    }

    private String finalizeShot(String body, Context ctx, boolean gpuPath) {
        StylePack pack = promptCatalog.stylePack(ctx.stylePreset());
        String text = body;
        if (gpuPath && ctx.applyDramaStyleLine()) {
            text = ensureDramaStyleLine(text, pack.id());
        }
        List<String> keywords = styleKeywords(ctx);
        text = PromptPrinciples.enforceStyleKeywords(text, keywords, pack.id());
        text = PromptPrinciples.stripConflictingMediumTerms(text, pack);
        if (ctx.applyVideoPrefix()
                && pack.videoPrefix() != null
                && !pack.videoPrefix().isBlank()) {
            String prefix = ctx.stillFrame()
                    ? PromptPrinciples.stripVideoMotionForStill(pack.videoPrefix())
                    : pack.videoPrefix().trim();
            if (!prefix.isBlank() && !text.contains(prefix)) {
                text = PromptPrinciples.prependStyleReference(text, prefix);
            }
        }
        if (gpuPath) {
            String note = ctx.styleReferenceNote();
            if (ctx.stillFrame()) {
                note = PromptPrinciples.stripVideoMotionForStill(note);
            }
            text = PromptPrinciples.prependStyleReference(text, note);
        }
        if (ctx.stillFrame()) {
            text = PromptPrinciples.stripVideoMotionForStill(text);
        }
        return text;
    }

    private String ensureDramaStyleLine(String body, String stylePreset) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) {
            return text;
        }
        if (text.contains("【画面风格") || text.contains("画面风格：")) {
            return text;
        }
        return promptCatalog.style(stylePreset) + "\n" + text;
    }

    private List<String> styleKeywords(Context ctx) {
        if (ctx.characterPromptHints() != null && !ctx.characterPromptHints().isEmpty()) {
            return PromptPrinciples.extractCommonStyleKeywords(ctx.characterPromptHints(), ctx.stylePreset());
        }
        return PromptPrinciples.extractCommonStyleKeywords(
                PromptPrinciples.characterPromptsFromAssets(ctx.linkedAssets()), ctx.stylePreset());
    }

    private static String typeKey(AssetType type) {
        return type == null ? "" : type.name();
    }

    private static AssetType parseType(String typeKey) {
        if (typeKey == null || typeKey.isBlank()) {
            return null;
        }
        try {
            return AssetType.valueOf(typeKey.trim().toLowerCase());
        } catch (Exception e) {
            return null;
        }
    }
}
