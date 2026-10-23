package com.storyflow.ai;

import com.storyflow.config.StoryflowProperties;

/**
 * 作品级画幅比例 → 像素尺寸。视频走 H3 常用分辨率；参考图/资产图走接近 1K 的尺寸。
 */
public final class AspectRatioSupport {

    public static final String RATIO_16_9 = "16:9";
    public static final String RATIO_9_16 = "9:16";
    public static final String RATIO_1_1 = "1:1";
    public static final String RATIO_4_3 = "4:3";
    public static final String RATIO_3_4 = "3:4";

    public record Size(int width, int height) {}

    private AspectRatioSupport() {}

    public static String normalizeVideoRatio(String raw) {
        String r = normalize(raw);
        return switch (r) {
            case RATIO_9_16, RATIO_1_1, RATIO_4_3, RATIO_3_4 -> r;
            default -> RATIO_16_9;
        };
    }

    public static String normalizeImageRatio(String raw) {
        String r = normalize(raw);
        return switch (r) {
            case RATIO_16_9, RATIO_9_16, RATIO_4_3, RATIO_3_4 -> r;
            default -> RATIO_1_1;
        };
    }

    /** 视频默认：16:9 → 配置 defaultWidth×defaultHeight（算力默认 864×480） */
    public static Size resolveVideoSize(String ratio, StoryflowProperties.Media media) {
        String r = normalizeVideoRatio(ratio);
        int baseW = media != null ? media.getDefaultWidth() : 864;
        int baseH = media != null ? media.getDefaultHeight() : 480;
        return switch (r) {
            case RATIO_9_16 -> new Size(baseH, baseW); // 竖屏对调
            case RATIO_1_1 -> {
                int s = Math.min(baseW, baseH);
                // H3 更稳的方形：取接近 640 且为 16 倍数
                int side = roundToMultiple(Math.max(s, 512), 16);
                yield new Size(side, side);
            }
            case RATIO_4_3 -> new Size(roundToMultiple(640, 16), roundToMultiple(480, 16));
            case RATIO_3_4 -> new Size(roundToMultiple(480, 16), roundToMultiple(640, 16));
            default -> new Size(baseW, baseH); // 16:9
        };
    }

    /** 参考图/资产图默认：1:1 → imageDefaultWidth×Height（1024×1024） */
    public static Size resolveImageSize(String ratio, StoryflowProperties.Media media) {
        String r = normalizeImageRatio(ratio);
        int base = media != null ? Math.max(media.getImageDefaultWidth(), media.getImageDefaultHeight()) : 1024;
        base = roundToMultiple(base, 8);
        return switch (r) {
            case RATIO_16_9 -> new Size(base, roundToMultiple(base * 9 / 16, 8));
            case RATIO_9_16 -> new Size(roundToMultiple(base * 9 / 16, 8), base);
            case RATIO_4_3 -> new Size(base, roundToMultiple(base * 3 / 4, 8));
            case RATIO_3_4 -> new Size(roundToMultiple(base * 3 / 4, 8), base);
            default -> new Size(base, base); // 1:1
        };
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.trim().replace('：', ':').replace('×', 'x').toLowerCase()
                .replace('x', ':');
    }

    private static int roundToMultiple(int value, int multiple) {
        if (multiple <= 1) {
            return Math.max(value, 1);
        }
        return Math.max(multiple, Math.round(value / (float) multiple) * multiple);
    }
}
