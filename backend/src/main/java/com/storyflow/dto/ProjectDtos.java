package com.storyflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

public class ProjectDtos {

    @Data
    public static class CreateRequest {
        @NotBlank(message = "项目名称不能为空")
        private String title;
        private String stylePreset;
    }

    @Data
    public static class UpdateRequest {
        private String title;
        private String stylePreset;
        /** 人物/场景/道具生图前缀后缀 */
        private ElementStylesDto elementStyles;
        /** first_last | single | none */
        private String refPromptMode;
        /** 视频画幅：16:9 | 9:16 | 1:1 | 4:3 | 3:4 */
        private String videoAspectRatio;
        /** 分镜参考图画幅 */
        private String refImageAspectRatio;
        /** 人物/场景/道具资产图画幅 */
        private String assetImageAspectRatio;
        /** 风格参考文字描述（「风格参考」，生图时前置） */
        private String styleReferenceNote;
    }

    @Data
    public static class ElementStyleDto {
        private String prefix;
        private String suffix;
    }

    @Data
    public static class ElementStylesDto {
        private ElementStyleDto character;
        private ElementStyleDto scene;
        private ElementStyleDto prop;
    }

    @Data
    public static class ProjectResponse {
        private Long id;
        private String title;
        private String stylePreset;
        private ElementStylesDto elementStyles;
        private String refPromptMode;
        private String videoAspectRatio;
        private String refImageAspectRatio;
        private String assetImageAspectRatio;
        private String styleReferenceNote;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private int scriptCount;
        private int shotCount;
        /** 当前用户仅可浏览、不可编辑 */
        private boolean readOnly;
    }
}
