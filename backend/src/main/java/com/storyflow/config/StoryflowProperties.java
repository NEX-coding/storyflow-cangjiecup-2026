package com.storyflow.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "storyflow")
public class StoryflowProperties {

    private Ollama ollama = new Ollama();
    private Assistant assistant = new Assistant();
    private Media media = new Media();
    private Mail mail = new Mail();
    private Auth auth = new Auth();
    private Quota quota = new Quota();

    @Getter
    @Setter
    public static class Ollama {
        private String baseUrl = "http://localhost:11434";
        /** 主端点不可用时按顺序尝试备用模型端点 */
        private List<String> fallbackUrls = new ArrayList<>();
        /** 可选：状态页，仅用于健康探测展示 */
        private String statusUrl = "";
        private String model = "qwen3.5:4b";
        /**
         * 本地文生图 / 图生图模型（Ollama image）。例：x/flux2-klein:4b
         * 与文本 model 分离；生图不再走 Comfy/SDXL HTTP。
         */
        private String imageModel = "x/flux2-klein:4b-fp8";
        /**
         * 生图专用 Ollama 地址。默认本机；勿留空（空串会导致 RestClient「URI is not absolute」）。
         * 文本可走 edu 穿透，生图通常仍走本机 http://127.0.0.1:11434
         */
        private String imageBaseUrl = "http://127.0.0.1:11434";
        /** 第一备用端点模型（如 model-api-mac / qwen3.5:4b）；留空则与主模型相同 */
        private String fallbackModel = "";
        /** 第二备用端点模型（如 model-api / qwen3.5:0.8b）；留空则与 fallbackModel 相同 */
        private String secondaryFallbackModel = "";
        /** 仅 sfxapp 本机：model-api-* 公网 URL 换成本地 11435/11436（同机 nginx 落地） */
        private boolean localModelApiRemap = false;
        private double temperature = 0.3;
        private int maxTokens = 4096;
        /** Ollama context window; must exceed prompt + completion to avoid truncated JSON. */
        private int numCtx = 16384;
        private int maxRetries = 3;
    }

    /**
     * AI 助手大脑：hybrid = Codex（cursor-agent）优先，失败回退 Ollama；
     * codex = 仅 Codex；ollama = 仅现有文本模型。
     * 创作意图（写原文/剧本/资产/分镜）始终走现有工具链，不受此配置影响。
     */
    @Getter
    @Setter
    public static class Assistant {
        /** hybrid | codex | ollama */
        private String provider = "hybrid";
        /** cursor-agent / agent 可执行文件；留空则自动探测 PATH 与 ~/.local/bin */
        private String codexBin = "";
        /** 传给 cursor-agent 的模型；留空用账号默认 */
        private String codexModel = "";
        /** ask 模式工作区（只读问答）；留空用进程 cwd */
        private String codexWorkspace = "";
        private long codexTimeoutMs = 120_000;
        /** 是否在状态接口暴露 Codex 可用性 */
        private boolean exposeStatus = true;
    }

    @Getter
    @Setter
    public static class Media {
        /**
         * 本地 MiniMax H3（ComfyUI）HTTP API。
         * 例：http://127.0.0.1:8090 或 http://127.0.0.1:6003/wan（FRP 历史路径名，不是通义万相）。
         */
        private String h3BaseUrl = "http://127.0.0.1:8090";
        /** @deprecated 兼容旧配置 wan-base-url；请改用 h3-base-url */
        private String wanBaseUrl = "";
        /** 本机 LTX-2.3 MLX（nex video-server），默认 http://127.0.0.1:8790 */
        private String mlxBaseUrl = "http://127.0.0.1:8790";
        /**
         * @deprecated 已改为 Ollama 本机生图（storyflow.ollama.image-model）。保留字段仅兼容旧配置/管理端回读。
         */
        private String imageBaseUrl = "";
        private String apiToken = "";
        /**
         * 本机媒体落盘目录。仅 edu compute 配置（如 /opt/storyflow-media/videos）。
         * GPU 机勿配，避免占满磁盘。
         */
        private String storageDir = "";
        /**
         * 远程保险库根地址（GPU 用）。例：https://edu.sfxapp.com/storyflow/media-vault
         * 对应 /videos 上传与回源。
         */
        private String archiveBaseUrl = "";
        /**
         * 已归档参考图公网路径前缀（edu compute）。例：/storyflow/asset-media
         * 由 nginx alias 到 storage-dir，浏览器可直接加载，无需 Bearer。
         */
        private String publicAssetBaseUrl = "";
        /** 公网站点根 URL，供 MiniMax 拉取参考图。例：https://edu.sfxapp.com */
        private String publicSiteBaseUrl = "https://edu.sfxapp.com";
        /** 视频生成已下线；保留字段仅兼容旧配置 / 历史成片回放。 */
        private String videoProvider = "disabled";
        private String doubaoExtensionId = "";
        private String geeknowBaseUrl = "https://api.geeknow.top";
        private String geeknowApiKey = "";
        private String geeknowProxyUrl = "";
        private String geeknowVideoMode = "FAST";
        private boolean geeknowVideoEnabled = false;
        private long geeknowPollIntervalMs = 5000;
        private long geeknowPollTimeoutMs = 3_600_000;

        /** 解析本地 H3 服务地址（兼容旧 wan-base-url） */
        public String resolveH3BaseUrl() {
            if (h3BaseUrl != null && !h3BaseUrl.isBlank()) {
                return h3BaseUrl;
            }
            if (wanBaseUrl != null && !wanBaseUrl.isBlank()) {
                return wanBaseUrl;
            }
            return "http://127.0.0.1:8090";
        }

        public String resolveMlxBaseUrl() {
            if (mlxBaseUrl != null && !mlxBaseUrl.isBlank()) {
                return mlxBaseUrl;
            }
            return "http://127.0.0.1:8790";
        }

        private String minimaxApiKey = "";
        private String minimaxBaseUrl = "https://api.minimaxi.com";
        private String minimaxModel = "MiniMax-H3";
        private String minimaxResolution = "768P";
        private int minimaxDuration = 6;
        private String minimaxRatio = "16:9";
        private long minimaxPollIntervalMs = 5000;
        private long minimaxPollTimeoutMs = 300_000;
        private int defaultFrames = 97;
        private int defaultSteps = 20;
        private int defaultWidth = 832;
        private int defaultHeight = 480;
        private int imageDefaultWidth = 1024;
        private int imageDefaultHeight = 1024;
        private int imageDefaultSteps = 4;
    }

    @Getter
    @Setter
    public static class Mail {
        private String from = "";
        private int codeTtlMinutes = 10;
        private int resendIntervalSeconds = 60;
    }

    /** When enabled, unauthenticated API calls use a shared guest account. */
    @Getter
    @Setter
    public static class Auth {
        private boolean guestEnabled = false;
        private String guestEmail = "guest@storyflow.local";
        private String guestNickname = "访客";
        /** 启动时若不存在则创建管理端独立账号（用户名+密码，与用户端邮箱体系分离） */
        private boolean adminSeedEnabled = false;
        private String adminUsername = "admin";
        private String adminPassword = "";
        private String adminNickname = "管理员";
        /** 内部关联用邮箱，不对管理端登录暴露 */
        private String adminEmail = "admin@storyflow.local";
    }

    @Getter
    @Setter
    public static class Quota {
        private Guest guest = new Guest();

        @Getter
        @Setter
        public static class Guest {
            private int maxProjects = 3;
            private int dailyScriptGenerate = 1;
            private int dailyAssetExtract = 1;
            private int dailyShotsGenerate = 1;
            private int dailyAssistantChat = 5;
        }
    }
}
