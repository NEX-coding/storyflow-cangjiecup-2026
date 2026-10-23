package com.storyflow.dto;

import com.storyflow.entity.AssetType;
import com.storyflow.entity.ReferenceType;
import lombok.Data;

import java.util.List;

public class WorkspaceDtos {

    @Data
    public static class SourceTextRequest {
        private String content;
    }

    @Data
    public static class SourceTextResponse {
        private String content;
    }

    @Data
    public static class ScriptResponse {
        private Long id;
        private String time;
        private String location;
        private List<String> characters;
        private String action;
        private String dialogue;
        private String shot;
        private int orderIndex;
    }

    @Data
    public static class ScriptUpdateRequest {
        private String time;
        private String location;
        private List<String> characters;
        private String action;
        private String dialogue;
        private String shot;
    }

    @Data
    public static class AssetResponse {
        private Long id;
        private AssetType type;
        private String name;
        private String description;
        private String imagePrompt;
        private String referenceImageFile;
        private String referenceImageUrl;
    }

    @Data
    public static class AssetUpdateRequest {
        private AssetType type;
        private String name;
        private String description;
        private String imagePrompt;
    }

    @Data
    public static class ReferencePromptResponse {
        private Long id;
        private ReferenceType type;
        private String prompt;
        private String note;
        private int orderIndex;
        private String referenceImageFile;
        private String referenceImageUrl;
    }

    @Data
    public static class ReferenceImageGenerateRequest {
        private String prompt;
        private Integer width;
        private Integer height;
        private Integer numInferenceSteps;
    }

    @Data
    public static class ReferencePromptUpdateRequest {
        private String type;
        private String prompt;
        private String note;
    }

    @Data
    public static class ShotResponse {
        private Long id;
        private Long scriptId;
        private int orderIndex;
        private String videoPrompt;
        /** first_last | single | none；空表示继承作品默认 */
        private String videoRefMode;
        private List<Long> linkedAssetIds;
        private List<ReferencePromptResponse> referencePrompts;
    }

    @Data
    public static class ShotUpdateRequest {
        private String videoPrompt;
        private List<Long> linkedAssetIds;
        /** first_last | single | none；传空字符串可清除覆盖、回退作品默认 */
        private String videoRefMode;
    }

    /** 作品默认 / 指定分镜参考方式 */
    @Data
    public static class ApplyVideoRefModeRequest {
        /** first_last | single | none */
        private String mode;
        /**
         * 非空：只给这些分镜写覆盖（不改作品默认）。
         * 空：改作品默认；配合 syncAllShots 可统一全部。
         */
        private List<Long> shotIds;
        /** shotIds 为空时有效：true 清除各镜覆盖，全部继承作品默认 */
        private boolean syncAllShots = true;
    }

    @Data
    public static class ApplyVideoRefModeResponse {
        private String mode;
        private int clearedShotOverrides;
        private int updatedShotCount;
        private List<ShotResponse> shots;
    }

    @Data
    public static class ShotReorderRequest {
        /** 按新顺序排列的全部分镜 ID */
        private List<Long> shotIds;
    }

    @Data
    public static class ShotInsertRequest {
        /**
         * 插入到该分镜之后；为 null 时插入到最前。
         */
        private Long afterShotId;
    }

    @Data
    public static class ShotMergeRequest {
        /** 保留的分镜（合并目标） */
        private Long primaryShotId;
        /** 被合并进 primary 后删除的分镜 */
        private Long secondaryShotId;
    }

    @Data
    public static class ShotSplitRequest {
        /**
         * 在剧本正文中的拆分位置（字符下标）。
         * 为空时按空行或文本中点自动拆分。
         */
        private Integer splitAt;
    }

    @Data
    public static class AssetCreateRequest {
        private AssetType type;
        private String name;
        private String description;
        private String imagePrompt;
    }

    @Data
    public static class ShotBatchRequest {
        /** 为空或空列表表示全部分镜 */
        private List<Long> shotIds;
        /** 可选：批量改风格时先更新项目风格再重生 */
        private String stylePreset;
    }

    @Data
    public static class SnapshotResponse {
        private Long id;
        private String kind;
        private String label;
        private String createdAt;
    }

    @Data
    public static class ExportResponse {
        private Object data;
    }

    @Data
    public static class ShotVideoResponse {
        private Long id;
        private Long shotId;
        private String status;
        private String prompt;
        private String remoteFile;
        private String fileUrl;
        private String errorMessage;
        private Double elapsedSec;
        private Integer frames;
        private Integer width;
        private Integer height;
        private String createdAt;
    }

    @Data
    public static class AssetImageGenerateRequest {
        private String prompt;
        private Integer width;
        private Integer height;
        private Integer numInferenceSteps;
    }

    @Data
    public static class AssetImageBatchRequest {
        /** 为空或空列表表示全部资产 */
        private List<Long> assetIds;
    }
}
