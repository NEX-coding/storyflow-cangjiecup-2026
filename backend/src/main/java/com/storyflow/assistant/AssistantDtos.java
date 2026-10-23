package com.storyflow.assistant;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

public class AssistantDtos {

    @Data
    public static class ChatRequest {
        @NotBlank(message = "消息不能为空")
        private String message;
        private String focus;
        private Long focusId;
        /** 工作台当前勾选的分镜 ID，用于「改这些」类指令 */
        private List<Long> selectedShotIds;
        /**
         * 危险操作（删除分镜/剧本/资产/章节、覆盖原文）须为 true 才会执行。
         * 前端弹窗确认后传入。
         */
        private Boolean confirmed;
        /**
         * 二次确认重试：不再重复落库用户消息（避免历史出现两遍同一句）。
         */
        private Boolean resumeConfirm;
    }

    @Data
    public static class MessageResponse {
        private Long id;
        private String role;
        private String content;
        private String createdAt;
        private boolean sourceUpdated;
        private boolean shotsUpdated;
        private boolean scriptsUpdated;
        private boolean assetsUpdated;
        private List<String> appliedActions;
        /** 需要前端弹窗确认后再带 confirmed=true 重试 */
        private boolean needsConfirm;
        private String confirmKind;
        private String confirmSummary;
    }
}
