package com.storyflow.ai;

/**
 * 风格包（StoryFlow 风格包）：
 * 构图 / 画风 / 媒介 / 视频前置 分文件，Java 只编排不写散文。
 */
public record StylePack(
        String id,
        /** lineart | paint | photo | cg | ink | cyber | film */
        String medium,
        /** anime_turnaround | photo_bust_sheet | cg_turnaround */
        String sheetLayout,
        String overview,
        String characterPrefix,
        String scenePrefix,
        String propPrefix,
        String videoPrefix,
        String negative,
        /** 含 {name} {look} {style} 占位符的设定板骨架 */
        String sheetTemplate
) {
    public static final String MEDIUM_LINEART = "lineart";
    public static final String MEDIUM_PAINT = "paint";
    public static final String MEDIUM_PHOTO = "photo";
    public static final String MEDIUM_CG = "cg";
    public static final String MEDIUM_INK = "ink";
    public static final String MEDIUM_CYBER = "cyber";
    public static final String MEDIUM_FILM = "film";

    public static final String LAYOUT_ANIME = "anime_turnaround";
    public static final String LAYOUT_PHOTO = "photo_bust_sheet";
    public static final String LAYOUT_CG = "cg_turnaround";

    public boolean allowsLineArt() {
        return MEDIUM_LINEART.equals(medium);
    }

    public boolean isPhotoLike() {
        return MEDIUM_PHOTO.equals(medium) || MEDIUM_FILM.equals(medium);
    }

    public String prefixFor(String assetType) {
        String t = assetType == null ? "" : assetType.trim().toLowerCase();
        return switch (t) {
            case "scene" -> scenePrefix == null ? "" : scenePrefix;
            case "prop" -> propPrefix == null ? "" : propPrefix;
            default -> characterPrefix == null ? "" : characterPrefix;
        };
    }

    public String renderSheet(String name, String look) {
        String template = sheetTemplate == null || sheetTemplate.isBlank()
                ? defaultAnimeSheet()
                : sheetTemplate;
        String n = name == null || name.isBlank() ? "主角" : name.trim();
        String l = look == null || look.isBlank() ? "外形与服装待补充" : look.trim();
        return template
                .replace("{name}", n)
                .replace("{look}", l)
                .replace("{style}", id == null ? "" : id);
    }

    public static String defaultAnimeSheet() {
        return "角色设定参考图，横构图16:9，左侧正脸头肩特写，右侧正面全身、90度侧面全身、背面全身三张等高横排，"
                + "中性A字站姿双手自然下垂空手，表情平静，特写与全身同一人同一发型服装，纯白背景柔和均匀光，无文字标签，"
                + "{name}，{look}，只表现外形与一套服装（上装内搭下装鞋最多一件配饰），无手持道具，"
                + "{style}，无文字水印";
    }

    public static String defaultPhotoSheet() {
        return "人物形象：艺术性16:9角色设定图；左侧约2/3为三等分竖向全身面板（正面躯干无头、90度侧面躯干无头、背面全身含头），"
                + "右侧约1/3为正脸与脖子特写；纯白背景，无环境无道具无水印；"
                + "{name}，{look}，只一套服装，无手持道具；"
                + "{style}，真实皮肤纹理，真实布料，自然光影，无过度磨皮，无文字水印";
    }

    public static String defaultCgSheet() {
        return "写实3D角色设定图，16:9，左侧正视图与背视图全身，右侧面部特写，白色背景，中性站姿，"
                + "{name}，{look}，PBR材质，棚拍灯光，禁止道具；"
                + "{style}，无文字水印";
    }
}
