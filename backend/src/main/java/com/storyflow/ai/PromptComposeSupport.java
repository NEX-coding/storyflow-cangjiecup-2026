package com.storyflow.ai;

import com.storyflow.entity.Asset;
import com.storyflow.entity.AssetType;
import com.storyflow.entity.ReferenceType;
import com.storyflow.entity.ScriptEntry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 提示词拼装（构图头 / 本地兜底正文）；风格收尾一律走 {@link PromptPipeline}。
 */
@Component
public class PromptComposeSupport {

    private final PromptCatalog promptCatalog;
    private final PromptPipeline promptPipeline;

    public PromptComposeSupport(PromptCatalog promptCatalog, PromptPipeline promptPipeline) {
        this.promptCatalog = promptCatalog;
        this.promptPipeline = promptPipeline;
    }

    /** 若正文尚无画面风格标记，则前置画风句（不做画风词/视频前置；GPU 路径请用 {@link #finalizeForGpu}） */
    public String ensureDramaStyle(String body, String stylePreset) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) {
            return text;
        }
        if (text.contains("【画面风格") || text.contains("画面风格：")) {
            return text;
        }
        return promptCatalog.style(stylePreset) + "\n" + text;
    }

    public String wrapAssetImagePrompt(
            String body,
            AssetType type,
            String stylePreset,
            ProjectSettingsSupport.ElementStyle elementStyle) {
        return wrapAssetImagePrompt(body, type, stylePreset, elementStyle, null);
    }

    public String wrapAssetImagePrompt(
            String body,
            AssetType type,
            String stylePreset,
            ProjectSettingsSupport.ElementStyle elementStyle,
            String styleReferenceNote) {
        return promptPipeline.finalize(
                PromptPipeline.Target.ASSET_GPU,
                body,
                PromptPipeline.Context.assetGpu(stylePreset, type, elementStyle, styleReferenceNote));
    }

    public String wrapAssetImagePrompt(String body, AssetType type, String stylePreset) {
        return wrapAssetImagePrompt(body, type, stylePreset, null, null);
    }

    /**
     * 分镜参考图合成与一致性约束。
     * 用 (@名称) 引用关联资产；单图模式额外注入人物外形摘要。
     */
    public String composeReferencePrompt(
            String refPrompt,
            String refNote,
            ReferenceType refType,
            List<Asset> linkedAssets,
            String stylePreset) {
        return composeReferencePrompt(refPrompt, refNote, refType, linkedAssets, stylePreset, false);
    }

    public String composeReferencePrompt(
            String refPrompt,
            String refNote,
            ReferenceType refType,
            List<Asset> linkedAssets,
            String stylePreset,
            boolean singleMainRef) {
        StringBuilder sb = new StringBuilder();
        String styleLine = promptCatalog.style(stylePreset);
        String body = nullToEmpty(refPrompt).trim();
        if (!body.contains("【画面风格") && !body.contains("画面风格：")) {
            sb.append(styleLine).append("\n");
        }
        boolean alreadyBoard = body.contains("【叙事分镜网格") || body.contains("【故事版")
                || body.contains("【底部故事版") || body.contains("【顶部参考条")
                || body.contains("【分镜图版") || body.contains("电影分镜故事板")
                || body.contains("视觉规划故事板") || body.contains("SC01");
        boolean stillFrame = !singleMainRef && (refType == ReferenceType.first_frame
                || refType == ReferenceType.last_frame
                || refType == ReferenceType.other);
        if (singleMainRef) {
            if (!alreadyBoard) {
                sb.append(storyboardBoardLayoutHeader()).append("\n");
            }
        } else if (refType == ReferenceType.first_frame) {
            sb.append("【首帧 · 起幅 · 静帧】16:9 单帧电影静帧（一个冻结瞬间；禁止运镜/时长；禁止角色设定板/三视图）。\n");
        } else if (refType == ReferenceType.last_frame) {
            sb.append("【尾帧 · 落幅 · 静帧】16:9 单帧电影静帧；与首帧同一人同一服装同一场景；仅姿态/光态/道具接触为主变；禁止运镜/时长/设定板。\n");
        } else {
            sb.append("【参考画面 · 静帧】16:9 单帧电影静帧；禁止运镜/时长/设定板。\n");
        }
        if (!body.isEmpty()) {
            sb.append(stillFrame ? PromptPrinciples.stripVideoMotionForStill(body) : body).append("\n");
        }
        if (refNote != null && !refNote.isBlank()) {
            String note = stillFrame
                    ? PromptPrinciples.stripVideoMotionForStill(refNote.trim())
                    : refNote.trim();
            if (!note.isBlank()) {
                sb.append(refType == ReferenceType.first_frame ? "起幅："
                        : refType == ReferenceType.last_frame ? "落幅：" : "说明：")
                        .append(note).append("\n");
            }
        }
        if (linkedAssets != null && !linkedAssets.isEmpty()) {
            List<String> mentions = new ArrayList<>();
            for (Asset asset : linkedAssets) {
                mentions.add(mention(asset));
            }
            sb.append("\n【关联资产】").append(String.join("、", mentions)).append("\n");
            boolean hasRefImages = linkedAssets.stream()
                    .anyMatch(a -> a.getReferenceImageFile() != null && !a.getReferenceImageFile().isBlank());
            if (singleMainRef || stillFrame) {
                String looks = characterLookBriefs(linkedAssets);
                if (!looks.isEmpty() && !body.contains("【身份锁定") && !body.contains("【人物外形锁定")) {
                    sb.append(stillFrame ? "【身份锁定】首尾同人：" : "【人物外形锁定】跨格同一人：")
                            .append(looks.replace("\n", "；")).append("\n");
                }
                String places = sceneLookBriefs(linkedAssets);
                if (!places.isEmpty() && !body.contains("【场景锁定")) {
                    sb.append("【场景锁定】").append(places).append("\n");
                }
                String props = propLookBriefs(linkedAssets);
                if (!props.isEmpty() && !body.contains("【道具状态")) {
                    sb.append("【道具状态】").append(props).append("\n");
                }
                if (singleMainRef && !alreadyBoard) {
                    sb.append(storyboardBoardForbidSheetNoise()).append("\n");
                }
                if (stillFrame && !body.contains("【严禁】")) {
                    sb.append(stillFrameForbidSheetNoise()).append("\n");
                }
                if (stillFrame && !body.contains("环境必须铺满") && !body.contains("禁止白底")) {
                    sb.append(stillFrameFullBleedRule()).append("\n");
                }
            }
            if (!body.contains("【一致性约束")) {
                sb.append("【一致性约束】凡写 (@名称) 的角色/场景/道具须与资产库同一身份与当前造型、同一空间；");
                sb.append("跨格保持脸型五官、年龄感、发型、体型、服装结构与配色；禁止换脸、换装、年龄漂移、多余肢体。");
                if (hasRefImages) {
                    if (singleMainRef) {
                        sb.append(" 已有资产参考图时：仅作外形真源；三视图/环境图只放顶部参考条，底部须为五格叙事分镜，不得整板铺满设定板原图。");
                    } else if (stillFrame) {
                        sb.append(" 已有资产参考图时：仅锁身份与服装材质，输出必须是单帧剧照构图，禁止抄设定板三视图排版。");
                    }
                }
                sb.append(" 若暂无元素参考图，仍按名称保持人物与场景前后一致。\n");
            }
        }
        if (singleMainRef && !body.contains("【严禁】") && !body.contains("不得整板做成")) {
            sb.append(storyboardBoardForbidSheetNoise()).append("\n");
        }
        if (stillFrame && linkedAssets != null && !linkedAssets.isEmpty()
                && !sb.toString().contains("【严禁】")) {
            sb.append(stillFrameForbidSheetNoise()).append("\n");
        }
        if (singleMainRef && !body.contains("【板面文字")) {
            sb.append(storyboardBoardTextAnnotation()).append("\n");
        }
        String composed = stillFrame
                ? finalizeStillShotPrompt(sb.toString().trim(), linkedAssets, stylePreset)
                : finalizeShotPrompt(sb.toString().trim(), linkedAssets, stylePreset);
        return singleMainRef ? softenNoTextForStoryboard(composed) : composed;
    }

    public String composeReferencePrompt(
            String refPrompt,
            String refNote,
            ReferenceType refType,
            List<Asset> linkedAssets,
            String stylePreset,
            boolean singleMainRef,
            String styleReferenceNote) {
        String composed = composeReferencePrompt(refPrompt, refNote, refType, linkedAssets, stylePreset, singleMainRef);
        boolean stillFrame = !singleMainRef && (refType == ReferenceType.first_frame
                || refType == ReferenceType.last_frame
                || refType == ReferenceType.other);
        String note = stillFrame
                ? PromptPrinciples.stripVideoMotionForStill(styleReferenceNote)
                : styleReferenceNote;
        return PromptPrinciples.prependStyleReference(composed, note);
    }

    /** 分镜/参考图提示词：全片画风词 + strip + 风格包视频前置 */
    public String finalizeShotPrompt(String prompt, List<Asset> linkedAssets, String stylePreset) {
        return promptPipeline.finalize(
                PromptPipeline.Target.SHOT_PERSIST,
                prompt,
                PromptPipeline.Context.shotPersist(stylePreset, linkedAssets));
    }

    /** 首尾帧静帧：画质前置去运镜词 */
    public String finalizeStillShotPrompt(String prompt, List<Asset> linkedAssets, String stylePreset) {
        return promptPipeline.finalize(
                PromptPipeline.Target.SHOT_PERSIST,
                prompt,
                PromptPipeline.Context.shotPersistStill(stylePreset, linkedAssets));
    }

    public static String stillFrameForbidSheetNoise() {
        return "【严禁】输出单帧电影剧照，禁止角色设定板/三视图/多视角拼贴/白底棚拍排版；"
                + "禁止运镜与时长词；禁止多格故事版；禁止字幕水印；"
                + "环境必须铺满画幅，禁止左白右景的半抠图拼贴。";
    }

    /** 自定义参考图 / 视频送 GPU：补画风行 + 画风词 + videoPrefix + 用户参考 */
    public String finalizeForGpu(
            PromptPipeline.Target target,
            String prompt,
            String stylePreset,
            List<Asset> linkedAssets,
            String styleReferenceNote) {
        PromptPipeline.Target t = target == null ? PromptPipeline.Target.SHOT_GPU : target;
        return promptPipeline.finalize(
                t,
                prompt,
                PromptPipeline.Context.shotGpu(stylePreset, linkedAssets, styleReferenceNote, true));
    }

    /** 首尾帧静帧送 GPU：去运镜 + 满幅场景约束 */
    public String finalizeStillForGpu(
            String prompt,
            String stylePreset,
            List<Asset> linkedAssets,
            String styleReferenceNote) {
        String body = prompt == null ? "" : prompt.trim();
        if (!body.contains("环境必须铺满") && !body.contains("禁止白底")) {
            body = body + "\n" + stillFrameFullBleedRule();
        }
        return promptPipeline.finalize(
                PromptPipeline.Target.SHOT_GPU,
                body,
                PromptPipeline.Context.shotGpuStill(stylePreset, linkedAssets, styleReferenceNote));
    }

    public static String stillFrameFullBleedRule() {
        return "【画面】环境必须铺满整幅 16:9，人物完整融入夜景/场景，禁止白底留白、半身抠图、设定板拼贴、边框裁切。"
                + "排除：白底，纯白背景，设定板，三视图，角色拼贴，抠图，贴纸，透明背景，分割画面。";
    }

    public String mention(Asset asset) {
        String name = asset.getName() == null ? "未命名" : asset.getName().trim();
        boolean hasImage = asset.getReferenceImageFile() != null && !asset.getReferenceImageFile().isBlank();
        return hasImage ? "(@" + name + ")" : "@" + name;
    }

    public String localVideoPrompt(ScriptEntry script, List<Asset> linkedAssets, String stylePreset) {
        String styleLine = promptCatalog.style(stylePreset);
        String people = linkedAssets == null ? "" : linkedAssets.stream()
                .filter(a -> a.getType() == AssetType.character)
                .map(this::mention)
                .collect(Collectors.joining("、"));
        String place = linkedAssets == null ? "" : linkedAssets.stream()
                .filter(a -> a.getType() == AssetType.scene)
                .map(this::mention)
                .findFirst()
                .orElse(nullToEmpty(script != null ? script.getLocation() : null));
        String action = script != null ? nullToEmpty(script.getAction()) : "";
        String lens = script != null ? nullToEmpty(script.getShot()) : "";
        String time = script != null ? nullToEmpty(script.getTimeLabel()) : "";
        if (people.isEmpty() && script != null && script.getCharactersJson() != null) {
            people = script.getCharactersJson().replace("[", "").replace("]", "").replace("\"", "");
        }
        String motion = action.isEmpty() ? "推进剧情" : action;
        String styleRef = styleLine == null ? "" : styleLine.trim();
        if (styleRef.startsWith("【") && styleRef.contains("】")) {
            int close = styleRef.indexOf('】');
            if (close >= 0 && close + 1 < styleRef.length()) {
                styleRef = styleRef.substring(close + 1).trim();
            }
        }
        String who = people.isEmpty() ? "主角" : people;
        String where = place.isEmpty() ? "场景" : place;
        String overview = who + "在" + where
                + (action.isEmpty() ? "推进剧情" : "：" + clip(action, 36));
        List<String> lines = new ArrayList<>();
        if (!styleRef.isEmpty()) {
            lines.add("【风格参考】" + clip(styleRef, 80));
        }
        lines.add("【一句话概述】" + clip(overview, 40));
        lines.add("【动作】先建立站位与情境，随后" + motion + "，最后收束到情绪落点。");
        lines.add("【镜头】" + (lens.isEmpty() ? "中景，固定镜头" : lens));
        lines.add("【画面】时间：" + (time.isEmpty() ? "日" : time)
                + "；地点：" + where
                + "；人物：" + who);
        lines.add("【节奏】时长：6秒；台词时段镜头稳定不推拉；无字幕水印。");
        lines.add("【一致】角色与场景外形与资产库一致，关键道具状态不跳变。");
        return String.join("\n", lines);
    }

    /** 首尾帧模式本地视频提示词：只写两帧之间过渡。 */
    public String localFlfVideoPrompt(ScriptEntry script, List<Asset> linkedAssets, String stylePreset) {
        String styleLine = promptCatalog.style(stylePreset);
        String people = linkedAssets == null ? "" : linkedAssets.stream()
                .filter(a -> a.getType() == AssetType.character)
                .map(this::mention)
                .collect(Collectors.joining("、"));
        String place = linkedAssets == null ? "" : linkedAssets.stream()
                .filter(a -> a.getType() == AssetType.scene)
                .map(this::mention)
                .findFirst()
                .orElse(nullToEmpty(script != null ? script.getLocation() : null));
        String action = script != null ? nullToEmpty(script.getAction()) : "";
        String lens = script != null ? nullToEmpty(script.getShot()) : "";
        String dialogue = script != null ? nullToEmpty(script.getDialogue()) : "";
        if (people.isEmpty() && script != null && script.getCharactersJson() != null) {
            people = script.getCharactersJson().replace("[", "").replace("]", "").replace("\"", "");
        }
        String who = people.isEmpty() ? "主角" : people;
        String where = place.isEmpty() ? "场景" : place;
        String motion = action.isEmpty() ? "完成关键动作" : action;
        String styleRef = styleLine == null ? "" : styleLine.trim();
        if (styleRef.startsWith("【") && styleRef.contains("】")) {
            int close = styleRef.indexOf('】');
            if (close >= 0 && close + 1 < styleRef.length()) {
                styleRef = styleRef.substring(close + 1).trim();
            }
        }
        List<String> lines = new ArrayList<>();
        if (!styleRef.isEmpty()) {
            lines.add("【风格参考】" + clip(styleRef, 80));
        }
        lines.add("【首尾帧驱动】以首帧为起点、尾帧为终点；只生成两帧之间连续动作。");
        lines.add("【一句话概述】" + clip(who + "在" + where + "：" + clip(motion, 28), 40));
        lines.add("【过渡】先承接首帧姿态，随后" + motion + "，最后收束到尾帧落幅。");
        lines.add("【镜头】" + (lens.isEmpty() ? "中景，固定镜头" : lens));
        if (!dialogue.isBlank()) {
            lines.add("【声音】" + formatDialogueForVideo(dialogue) + "；成片无烧录字幕。");
        }
        lines.add("【一致】同一人设服装场景；不换脸；成片无烧录字幕。");
        lines.add("【节奏】时长：6秒；台词时段镜头稳定不推拉。");
        return String.join("\n", lines);
    }

    /**
     * 视频提示词确定性补台词：剧本 dialogue 非空时，必须出现原文（写入【声音】）。
     * 首尾帧 / 无参考 / 单图均适用；幂等。
     */
    public String ensureVideoDialogue(String videoPrompt, String dialogue) {
        String dial = nullToEmpty(dialogue).trim();
        String text = nullToEmpty(videoPrompt).trim();
        if (dial.isEmpty() || text.isEmpty()) {
            return text;
        }
        if (videoPromptCoversDialogue(text, dial)) {
            return text;
        }
        String voiceLine = "【声音】" + formatDialogueForVideo(dial) + "；成片无烧录字幕。";
        // 去掉旧的空壳/不完整【声音】行再插入
        text = text.replaceAll("(?m)^【声音】[^\\n]*\\n?", "").trim();
        if (text.contains("【一致】")) {
            return text.replaceFirst("【一致】", voiceLine + "\n【一致】");
        }
        if (text.contains("【节奏】")) {
            return text.replaceFirst("【节奏】", voiceLine + "\n【节奏】");
        }
        return text + "\n" + voiceLine;
    }

    static boolean videoPromptCoversDialogue(String prompt, String dialogue) {
        if (prompt == null || prompt.isBlank() || dialogue == null || dialogue.isBlank()) {
            return false;
        }
        for (String part : dialogue.split("[\\n]+")) {
            String t = part == null ? "" : part.trim();
            if (t.isEmpty()) {
                continue;
            }
            // 「角色名：台词」→ 至少台词核心要在提示词里
            String spoken = t;
            int colon = Math.max(t.indexOf('：'), t.indexOf(':'));
            if (colon >= 0 && colon + 1 < t.length()) {
                spoken = t.substring(colon + 1).trim();
            }
            spoken = spoken.replaceAll("[…\\.。！!？?\\s]+$", "").trim();
            if (spoken.length() >= 2 && !prompt.contains(spoken.substring(0, Math.min(spoken.length(), 8)))) {
                return false;
            }
            if (spoken.length() < 2 && !prompt.contains(t.substring(0, Math.min(t.length(), 6)))) {
                return false;
            }
        }
        return true;
    }

    static String formatDialogueForVideo(String dialogue) {
        List<String> parts = splitDialogueLines(dialogue);
        if (parts.isEmpty()) {
            return clip(dialogue.replace('\n', ' '), 160);
        }
        return String.join(" / ", parts);
    }

    /**
     * 单图模式本地视频提示词：按秒映射故事版底部五格区域。
     */
    public String localSingleVideoPrompt(ScriptEntry script, List<Asset> linkedAssets, String stylePreset) {
        String styleLine = promptCatalog.style(stylePreset);
        String people = linkedAssets == null ? "" : linkedAssets.stream()
                .filter(a -> a.getType() == AssetType.character)
                .map(this::mention)
                .collect(Collectors.joining("、"));
        String placeLabel = nullToEmpty(script != null ? script.getLocation() : null);
        String place = linkedAssets == null ? "" : linkedAssets.stream()
                .filter(a -> a.getType() == AssetType.scene)
                .map(this::mention)
                .findFirst()
                .orElse(placeLabel);
        String action = script != null ? nullToEmpty(script.getAction()) : "";
        String lens = script != null ? nullToEmpty(script.getShot()) : "";
        String time = script != null ? nullToEmpty(script.getTimeLabel()) : "";
        String dialogue = script != null ? nullToEmpty(script.getDialogue()) : "";
        if (people.isEmpty() && script != null && script.getCharactersJson() != null) {
            people = script.getCharactersJson().replace("[", "").replace("]", "").replace("\"", "");
        }
        if (people.isEmpty()) {
            people = "主角";
        }
        if (placeLabel.isEmpty()) {
            placeLabel = place.isEmpty() ? "场景" : place.replaceAll("[(@)]", "");
        }
        List<String> panels = deriveStoryboardPanels(time, placeLabel, people, action, dialogue, lens);
        int durationSec = 6;
        List<String> dialLines = splitDialogueLines(dialogue);
        List<String> timeline = buildRichStoryboardTimeline(panels, durationSec, dialLines, lens);
        String overview = (people.isEmpty() ? "主角" : people) + "在"
                + (place.isEmpty() ? placeLabel : place)
                + (action.isEmpty() ? "推进剧情" : "：" + clip(action, 36));
        // video.md 前置通常已是一行短句，作【风格参考】
        String styleRef = styleLine == null ? "" : styleLine.trim();
        if (styleRef.startsWith("【") && styleRef.contains("】")) {
            // 已是带标签的风格行，剥成短参考
            int close = styleRef.indexOf('】');
            if (close >= 0 && close + 1 < styleRef.length()) {
                styleRef = styleRef.substring(close + 1).trim();
            }
        }
        List<String> lines = new ArrayList<>();
        if (!styleRef.isEmpty()) {
            lines.add("【风格参考】" + clip(styleRef, 80));
        }
        lines.add("【故事版绑定】以本镜故事版底部叙事格为图生参考；只推进分镜01–05，顶部参考条仅锁人设与环境。");
        lines.add("【一句话概述】" + clip(overview, 40));
        lines.add("【故事版时间轴】时长：" + durationSec + "秒");
        lines.addAll(timeline);
        return String.join("\n", lines);
    }

    /** 将底部五格均分到时长，生成「秒 → 分镜区域」行（简版，供 GPU 补全）。 */
    public static List<String> buildStoryboardTimeline(List<String> panels, int durationSec) {
        return buildRichStoryboardTimeline(panels, durationSec, List.of(), "");
    }

    /**
     * 精细时间轴：秒段｜格｜景别｜运镜｜动作｜台词/声。
     * 对齐 Wan/Seedance/可灵「多镜时间戳」写法。
     */
    public static List<String> buildRichStoryboardTimeline(
            List<String> panels, int durationSec, List<String> dialLines, String lensHint) {
        List<String> out = new ArrayList<>();
        if (panels == null || panels.isEmpty()) {
            return out;
        }
        int n = panels.size();
        int dur = durationSec <= 0 ? 6 : durationSec;
        String[] sizes = {"全黑/极暗信息格", "中全景", "中景", "近景特写", "全景/俯拍收束"};
        String[] cams = {"固定", "固定微手持", "缓慢推近", "固定微推", "微仰固定后轻微下摇"};
        for (int i = 0; i < n; i++) {
            double start = (dur * (double) i) / n;
            double end = (dur * (double) (i + 1)) / n;
            String size = i < sizes.length ? sizes[i] : (lensHint == null || lensHint.isBlank() ? "中景" : lensHint);
            String cam = i < cams.length ? cams[i] : "固定";
            String beat = clip(panels.get(i), 90);
            String voice = "";
            if (dialLines != null && !dialLines.isEmpty()) {
                if (i == 1) {
                    voice = "｜台词「" + dialLines.get(0) + "」";
                } else if (i == 2 && dialLines.size() > 1) {
                    voice = "｜台词「" + dialLines.get(1) + "」";
                } else if (i == 3) {
                    voice = "｜环境声骤静，呼吸可闻";
                } else if (i == 0) {
                    voice = "｜环境底噪极低";
                } else if (i == 4) {
                    voice = "｜落地闷响与尘土摩擦声";
                }
            } else if (i == 0) {
                voice = "｜环境底噪";
            }
            out.add(String.format(
                    "%.1f–%.1fs → 分镜%02d｜%s｜%s｜%s%s",
                    start, end, i + 1, size, cam, clip(beat, 56), voice));
        }
        return out;
    }

    /**
     * 送 GPU 前：若单图模式且正文缺少故事版时间轴，补一段通用映射说明。
     */
    public String ensureStoryboardTimelineForGpu(String videoPrompt, int durationSec) {
        String text = nullToEmpty(videoPrompt).trim();
        if (text.isEmpty()) {
            return text;
        }
        if (text.contains("【故事版时间轴】") || text.contains("底部左起第")) {
            return text;
        }
        int dur = durationSec == 10 ? 10 : 6;
        List<String> panels = List.of(
                "时空信息/黑场交代",
                "建立镜头：环境与人物站位",
                "核心动作跟拍",
                "情绪近景/特写",
                "反应或收束镜头");
        List<String> timeline = buildStoryboardTimeline(panels, dur);
        StringBuilder sb = new StringBuilder(text);
        sb.append("\n【故事版驱动】以本镜故事版参考图为图生首帧；顶部参考条只锁人设/环境，主画面推进底部五格。");
        sb.append("\n【故事版时间轴】时长：").append(dur).append("秒：");
        for (String row : timeline) {
            sb.append("\n").append(row);
        }
        return sb.toString();
    }

    public String localFirstFramePrompt(ScriptEntry script, List<Asset> linkedAssets, String stylePreset) {
        return localFramePrompt(script, linkedAssets, stylePreset, true);
    }

    public String localLastFramePrompt(ScriptEntry script, List<Asset> linkedAssets, String stylePreset) {
        return localFramePrompt(script, linkedAssets, stylePreset, false);
    }

    /**
     * 单图模式本地兜底：上约20%角色+环境参考条，下约80%横向五格叙事分镜。
     */
    public String localSingleRefPrompt(ScriptEntry script, List<Asset> linkedAssets, String stylePreset) {
        String styleName = stylePreset == null || stylePreset.isBlank() ? "默认" : stylePreset.trim();
        String styleLine = promptCatalog.style(stylePreset);
        String people = linkedAssets == null ? "" : linkedAssets.stream()
                .filter(a -> a.getType() == AssetType.character)
                .map(this::mention)
                .collect(Collectors.joining("、"));
        String place = linkedAssets == null ? "" : linkedAssets.stream()
                .filter(a -> a.getType() == AssetType.scene)
                .map(this::mention)
                .findFirst()
                .orElse(nullToEmpty(script != null ? script.getLocation() : null));
        String placeLabel = nullToEmpty(script != null ? script.getLocation() : null);
        if (placeLabel.isEmpty()) {
            placeLabel = place.isEmpty() ? "场景" : place.replaceAll("[(@)]", "");
        }
        String action = script != null ? nullToEmpty(script.getAction()) : "推进剧情";
        String lens = script != null ? nullToEmpty(script.getShot()) : "";
        String time = script != null ? nullToEmpty(script.getTimeLabel()) : "";
        String dialogue = script != null ? nullToEmpty(script.getDialogue()) : "";
        if (people.isEmpty() && script != null && script.getCharactersJson() != null) {
            people = script.getCharactersJson().replace("[", "").replace("]", "").replace("\"", "");
        }
        if (people.isEmpty()) {
            people = "主角";
        }
        if (place.isEmpty()) {
            place = placeLabel;
        }
        String looks = characterLookBriefs(linkedAssets);
        String scenes = sceneLookBriefs(linkedAssets);
        String props = propLookBriefs(linkedAssets);
        List<String> panels = deriveStoryboardPanels(time, placeLabel, people, action, dialogue, lens);
        String micro = clip((people.isEmpty() ? "主角" : people) + "在" + placeLabel
                + (action.isEmpty() ? "推进剧情" : "：" + action), 40);
        List<String> lines = new ArrayList<>();
        lines.add("【画面风格｜" + styleName + "】" + styleLine);
        lines.add("【项目卡】标题：" + clip(placeLabel + "·" + (action.isEmpty() ? "本镜" : action), 12)
                + "｜元信息：" + styleName + " / 6秒 / 动作可读·屏幕方向·镜头意图"
                + "｜微简述：" + micro);
        lines.add(storyboardBoardLayoutHeader());
        lines.add("【连续性】起始屏幕位置：主体偏中或偏左；主屏幕方向：按剧情左右推进；"
                + "地理锚点：" + placeLabel + " 门/灯/台面等最少形；"
                + "起始状态：" + clip(action.isEmpty() ? "人物就位" : action, 60)
                + "；结束状态：本镜动作兑现后的站位与道具状态。");
        if (!props.isEmpty()) {
            lines.add("道具状态：" + props);
        }
        lines.add("【顶部参考条 · 占高约18%｜仅锁身份】");
        lines.add("- 左侧「角色参考区」："
                + (looks.isEmpty()
                ? people + " 脸部特写+简三视（正/侧/背），外貌与元素库一致；不控制叙事构图"
                : looks.replace("\n", "；") + "；不控制叙事构图"));
        lines.add("- 右侧「环境参考区」：「" + placeLabel + "」锚点一帧，标注场景名"
                + (scenes.isEmpty() ? "" : "；" + scenes));
        lines.add("【底部叙事格 · 占高约82%｜制作预演】横排 5 个等大 16:9；每格一个冻结瞬间：");
        String[] beatNames = {"时空建立", "站位建立", "核心接触", "情绪兑现", "反应收束"};
        String[] cams = {"固定全景", "固定中全景", "微推中景", "固定近景", "俯拍/全景收束"};
        for (int i = 0; i < panels.size(); i++) {
            String beat = i < beatNames.length ? beatNames[i] : ("节拍" + (i + 1));
            String cam = i < cams.length ? cams[i] : (lens.isEmpty() ? "中景固定" : lens);
            String act = clip(panels.get(i), 80);
            lines.add("[分镜" + String.format("%02d", i + 1) + "] — " + beat);
            lines.add("SHOT NOTE：推进「" + beat + "」");
            lines.add("CAMERA：" + cam);
            lines.add("ACTION：" + act);
            lines.add("FOCUS：看清「" + beat + "」的空间与人物关系");
            lines.add("MARKS：红=镜头意图；蓝=身体路径；绿=主动作线；橙=碰撞/环境反应；紫=节奏（按需，2–6标）");
        }
        lines.add(storyboardBoardTextAnnotation());
        if (!dialogue.isEmpty()) {
            lines.add("台词须落在对应分镜框内或框下，原文可读：「" + clip(dialogue, 160) + "」");
        }
        lines.add(storyboardBoardForbidSheetNoise());
        lines.add("【一致性约束】(@名称) 与元素库同一人设/空间；参考图只锁身份；五官服饰道具不漂移。");
        return softenNoTextForStoryboard(String.join("\n", lines));
    }

    /** 画幅：上参考条 + 下五格制作预演板 */
    public static String storyboardBoardLayoutHeader() {
        return "画幅：横构图16:9制作预演板（Production Previs Board，非概念插画/海报）。"
                + "上约18%身份参考条（角色外形+场景锚点），下约82%横排五格叙事分镜。"
                + "白/浅灰工作底，粗黑线分隔；须含分镜标注文字，禁止平台水印/logo。"
                + "优先动作可读、屏幕方向、镜头意图与连续性。";
    }

    /** 故事版图上必须画出的专业标注（含台词） */
    public static String storyboardBoardTextAnnotation() {
        return "【板面文字 · 必须画在图上】"
                + "①每格左上角标「分镜01」…「分镜05」；"
                + "②顶部角色区标注角色名，环境区标注场景名称；"
                + "③分镜01 用白字写清时间·内外景·地点；"
                + "④有台词的格子在画面内气泡或框下字幕条写出「角色名：台词」原文，字迹清晰可读；"
                + "⑤可附简短景别/运镜小字（如 CU、缓慢推）；"
                + "禁止空板无字；禁止平台水印、网站 logo、广告字。"
                + "（成片视频另禁烧录字幕，与本制作板标注无关）";
    }

    /**
     * 风格包 video 前置常带「无文字/无字幕水印」，会压掉故事版标注；
     * 生故事版图时改为「禁水印、保留分镜标注」。
     */
    public static String softenNoTextForStoryboard(String prompt) {
        String text = nullToEmpty(prompt);
        if (text.isEmpty()) {
            return text;
        }
        text = text.replace("无文字无水印", "禁止平台水印，须含分镜标注文字");
        text = text.replace("无文字标签", "角色名与场景名须标注");
        text = text.replace("无文字水印", "禁止平台水印，须含分镜标注与台词文字");
        text = text.replace("无字幕水印", "禁止平台水印与广告字幕，须保留分镜标注与台词文字");
        // 单独「无文字」易误伤板面标注（真人写实风格包尤甚）
        text = text.replace("，无文字", "，须含分镜标注文字");
        text = text.replace("、无文字", "、须含分镜标注文字");
        text = text.replace("无文字，", "须含分镜标注文字，");
        text = text.replaceAll("无文字(?!水印)", "须含分镜标注文字");
        if (!text.contains("【板面文字") && !text.contains("须含分镜标注")) {
            text = text + "\n" + storyboardBoardTextAnnotation();
        }
        return text;
    }

    /**
     * 单图故事版：剧本有台词时，强制把原文写进提示词板面要求，避免模型改成「无声/欲言又止」。
     */
    public String ensureStoryboardDialogueOnBoard(String prompt, String dialogue) {
        String text = softenNoTextForStoryboard(prompt);
        String dial = nullToEmpty(dialogue).trim();
        if (dial.isEmpty()) {
            return text;
        }
        if (storyboardPromptCoversDialogue(text, dial)) {
            return text;
        }
        String compact = dial.replace("\r\n", "\n").replace("\n", " / ");
        String block = "【剧本台词 · 必须原样画在对应分镜框内或框下】"
                + compact
                + "。按说话人标注「角色名：台词」；禁止改写成「无声」「欲言又止」「无声惊叫」等；"
                + "对白格优先落在分镜02–04。";
        if (text.contains("【板面文字")) {
            return text + "\n" + block;
        }
        return text + "\n" + storyboardBoardTextAnnotation() + "\n" + block;
    }

    static boolean storyboardPromptCoversDialogue(String prompt, String dialogue) {
        String p = nullToEmpty(prompt);
        if (p.isEmpty()) {
            return false;
        }
        for (String part : dialogue.split("[\\n/｜|]+")) {
            String line = part == null ? "" : part.trim();
            // 去掉「角色名：」前缀再比内容
            int colon = Math.max(line.indexOf('：'), line.indexOf(':'));
            String content = colon >= 0 && colon + 1 < line.length() ? line.substring(colon + 1).trim() : line;
            content = content.replaceAll("[「」\"“”]", "").trim();
            if (content.length() >= 2 && p.contains(content)) {
                return true;
            }
            // 短关键词：两块、快点 等
            if (content.length() >= 2) {
                String key = content.length() <= 6 ? content : content.substring(0, Math.min(6, content.length()));
                if (p.contains(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 三视图只许在顶部参考条；底部必须是叙事分镜 */
    public static String storyboardBoardForbidSheetNoise() {
        return "【严禁】不得整板做成纯设定表拼贴、概念插画拼贴或道具产品图铺满；"
                + "人物三视图/脸部特写仅可出现在顶部参考条；底部五格必须是连续制作预演戏，"
                + "每格一个冻结动作瞬间，含环境锚点、人物剪影姿态、关键道具、镜头意图与角标/台词等板面文字。";
    }

    /**
     * 固定推导底部五格：时空信息 → 建立 → 核心动作 → 情绪特写 → 反应收束。
     */
    List<String> deriveStoryboardPanels(
            String time, String place, String people, String action, String dialogue, String lens) {
        String timePlace = (nullToEmpty(time).isEmpty() ? "日" : time.trim())
                + (nullToEmpty(place).isEmpty() ? "" : " · " + place.trim());
        String placeShow = place.isEmpty() ? "场景" : place;
        String peopleShow = people.isEmpty() ? "主角" : people;
        String actionTrim = nullToEmpty(action).trim();
        String coreAction = actionTrim.isEmpty() ? peopleShow + " 完成核心互动" : clip(actionTrim, 100);
        String lensHint = lens.isEmpty() ? "中景" : lens;
        List<String> panels = new ArrayList<>(5);
        String dial = nullToEmpty(dialogue).trim();
        List<String> dialLines = splitDialogueLines(dial);
        panels.add("全黑场或极暗空镜，框内白字清晰写：「" + timePlace + "」；角标分镜01");
        panels.add("建立镜头，展示 " + placeShow + " 环境与人物站位，" + peopleShow + " 入画；"
                + "前后景材质与纵深清晰；角标分镜02"
                + (dialLines.isEmpty() ? "" : "；框下台词「" + dialLines.get(0) + "」"));
        panels.add(lensHint + "跟拍核心动作：" + coreAction
                + "；人物肢体轨迹与表情清楚，道具接触可辨；角标分镜03"
                + (dialLines.size() > 1 ? "；框下台词「" + dialLines.get(1) + "」"
                : (dialLines.size() == 1 ? "；可再次标出台词「" + dialLines.get(0) + "」" : "")));
        if (!dial.isEmpty()) {
            panels.add("近景/特写，强调关键人物情绪与目光，" + peopleShow + " 主导画面；"
                    + "框内或框下必须原样写出台词「" + clip(dial.replace("\n", " / "), 100)
                    + "」；禁止改成无声；角标分镜04");
        } else {
            panels.add("近景/特写，强调关键人物情绪与目光，" + peopleShow
                    + " 主导画面；肤色高光与眼神光清晰；角标分镜04");
        }
        panels.add("反应镜头或收束镜头，承接下一拍；" + peopleShow
                + " 身体朝向与落点清楚；角标分镜05");
        return panels;
    }

    private static List<String> splitDialogueLines(String dialogue) {
        if (dialogue == null || dialogue.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String part : dialogue.split("[\\n]+")) {
            String t = part == null ? "" : part.trim();
            if (!t.isEmpty()) {
                lines.add(clip(t, 40));
            }
        }
        return lines;
    }

    /** @deprecated 单图模式请用 {@link #localSingleRefPrompt} */
    public String localStoryboardPrompt(ScriptEntry script, List<Asset> linkedAssets, String stylePreset) {
        return localSingleRefPrompt(script, linkedAssets, stylePreset);
    }

    private String characterLookBriefs(List<Asset> linkedAssets) {
        if (linkedAssets == null || linkedAssets.isEmpty()) {
            return "";
        }
        List<String> briefs = new ArrayList<>();
        for (Asset asset : linkedAssets) {
            if (asset.getType() != AssetType.character) {
                continue;
            }
            String name = nullToEmpty(asset.getName()).trim();
            if (name.isEmpty()) {
                name = "角色";
            }
            String look = firstNonBlank(asset.getDescription(), asset.getImagePrompt());
            look = PromptPrinciples.stripReferenceSheetNoise(look);
            if (look.isEmpty()) {
                briefs.add(mention(asset) + "（外形见资产库）");
            } else {
                briefs.add(mention(asset) + "：" + clip(look, 160));
            }
        }
        return String.join("\n", briefs);
    }

    private String sceneLookBriefs(List<Asset> linkedAssets) {
        if (linkedAssets == null || linkedAssets.isEmpty()) {
            return "";
        }
        List<String> briefs = new ArrayList<>();
        for (Asset asset : linkedAssets) {
            if (asset.getType() != AssetType.scene) {
                continue;
            }
            String look = firstNonBlank(asset.getDescription(), asset.getImagePrompt());
            look = PromptPrinciples.stripReferenceSheetNoise(look);
            if (look.isEmpty()) {
                briefs.add(mention(asset));
            } else {
                briefs.add(mention(asset) + "（" + clip(look, 120) + "）");
            }
        }
        return String.join("；", briefs);
    }

    private String propLookBriefs(List<Asset> linkedAssets) {
        if (linkedAssets == null || linkedAssets.isEmpty()) {
            return "";
        }
        List<String> briefs = new ArrayList<>();
        for (Asset asset : linkedAssets) {
            if (asset.getType() != AssetType.prop) {
                continue;
            }
            String look = firstNonBlank(asset.getDescription(), asset.getImagePrompt());
            look = PromptPrinciples.stripReferenceSheetNoise(look);
            if (look.isEmpty()) {
                briefs.add(mention(asset));
            } else {
                briefs.add(mention(asset) + "（" + clip(look, 80) + "）");
            }
        }
        return String.join("；", briefs);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return "";
    }

    private static String clip(String text, int max) {
        String t = nullToEmpty(text).trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, Math.max(0, max - 1)) + "…";
    }

    private String localFramePrompt(
            ScriptEntry script, List<Asset> linkedAssets, String stylePreset, boolean first) {
        String styleLine = promptCatalog.style(stylePreset);
        String people = linkedAssets == null ? "" : linkedAssets.stream()
                .filter(a -> a.getType() == AssetType.character)
                .map(this::mention)
                .collect(Collectors.joining("、"));
        String place = linkedAssets == null ? "" : linkedAssets.stream()
                .filter(a -> a.getType() == AssetType.scene)
                .map(this::mention)
                .findFirst()
                .orElse(nullToEmpty(script != null ? script.getLocation() : null));
        String action = script != null ? nullToEmpty(script.getAction()) : "推进剧情";
        String lens = script != null ? nullToEmpty(script.getShot()) : "";
        String time = script != null ? nullToEmpty(script.getTimeLabel()) : "";
        if (people.isEmpty()) {
            people = "主角";
        }
        if (place.isEmpty()) {
            place = "场景";
        }
        String looks = characterLookBriefs(linkedAssets);
        String places = sceneLookBriefs(linkedAssets);
        String header = first ? "【首帧 · 起幅 · 静帧】" : "【尾帧 · 落幅 · 静帧】";
        String shotSize = lens.isEmpty() ? "中景 · 平视" : clip(lens, 24);
        String beat = first
                ? people + " 动作刚起势的冻结瞬间；建立站位、表情、道具接触与环境光；一个瞬间，禁止「先…随后…」。"
                : "与首帧同一人同一服装同一场景；动作落到终点（" + clip(action, 60)
                + "）的冻结瞬间；姿态/光态相对首帧有明确主变。";
        List<String> lines = new ArrayList<>();
        lines.add(styleLine);
        lines.add("【画幅】16:9 单帧电影静帧（不是角色设定板、不是三视图、不是多格故事版）。");
        lines.add(header);
        lines.add("【景别·机位】" + shotSize);
        lines.add("【场景】" + place + (time.isEmpty() ? "" : "，" + time)
                + (places.isEmpty() ? "" : "；" + places));
        lines.add("【主体】" + people + "。" + beat);
        if (!looks.isEmpty()) {
            lines.add("【身份锁定】" + looks.replace("\n", "；"));
        }
        lines.add(first ? "【首尾配对】起幅冻结点" : "【首尾配对】落幅冻结点；须可与首帧物理插值");
        lines.add(stillFrameForbidSheetNoise());
        lines.add("【一致性约束】(@名称) 与资产库同一身份造型；已有参考图仅锁身份，禁止抄设定板排版。");
        return finalizeStillShotPrompt(String.join("\n", lines), linkedAssets, stylePreset);
    }

    public String localAssetImagePrompt(Asset asset, String stylePreset) {
        String name = nullToEmpty(asset.getName());
        String desc = nullToEmpty(asset.getDescription());
        return switch (asset.getType() == null ? AssetType.prop : asset.getType()) {
            case character -> promptCatalog.fallbackCharacterSheet(name, desc, stylePreset);
            case scene -> promptPipeline.normalizeAssetPersist(
                    PromptPrinciples.ensureStyleKeyword(
                            "空镜环境设定主视觉，禁止任何人物路人剪影，" + name + "，"
                                    + (desc.isEmpty() ? "空间层次清晰" : desc),
                            stylePreset),
                    AssetType.scene, stylePreset);
            case prop -> promptPipeline.normalizeAssetPersist(
                    PromptPrinciples.ensureStyleKeyword(
                            "道具多角度参考表，正面侧面俯视同页，白底，" + name + "，"
                                    + (desc.isEmpty() ? "材质清晰" : desc),
                            stylePreset),
                    AssetType.prop, stylePreset);
        };
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
