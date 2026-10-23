package com.storyflow.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.storyflow.ai.LlmGateway;
import com.storyflow.common.BusinessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ScriptLlmService {

    private final LlmJsonSupport json;

    public ScriptLlmService(LlmJsonSupport json) {
        this.json = json;
    }

    public List<LlmGateway.ScriptDto> generateScript(String sourceText, String style) {
        String system = json.getTemplate("script_generate");
        String user = """
                风格：%s
                将小说原文转为剧本。严格遵循系统规范（边界红线、可视化叙事、品类节奏、人物名一致）。
                严格输出 JSON：{"items":[...]}
                每条字段类型必须是：
                - time: 字符串（日/夜/晨/昏等）
                - location: 字符串（偏广可复用场景名，勿拆成沙发旁/茶几前）
                - characters: 字符串数组（人名与原文一致）
                - action: 字符串（可视化动作+环境+情绪外化；不要数组；不要写运镜参数）
                - dialogue: 字符串（不要数组，多人台词用换行拼接；长度匹配品类）
                - shot: 字符串（仅场次功能短备注如定场/钩子，不要分镜参数）
                
                重要：characters 中的人名须与原文一致，不得擅自改名或替换；若原文未给出人名可自行命名，但不要每次都固定成同一个人（例如不要总是「林风」）。
                不要输出欢迎语、DNA 总纲长文或纯文本场次剧本，只输出 JSON。
                
                原文：
                %s
                """.formatted(style, sourceText);
        JsonNode node = json.callJsonWithRetry(system, user);
        return parseScripts(node);
    }

    /** 按用户指令重写单条剧本，输出 items 仅 1 条 */
    public LlmGateway.ScriptDto rewriteScriptEntry(LlmGateway.ScriptDto current, String style, String instruction) {
        String system = json.getTemplate("script_generate");
        String user = """
                风格：%s
                用户修改要求：%s
                请基于当前剧本条目重写，只输出 1 条。严格 JSON：{"items":[{...}]}
                字段：time, location, characters(数组), action, dialogue, shot（均为字符串，characters 为字符串数组）
                
                当前条目：
                {"time":%s,"location":%s,"characters":%s,"action":%s,"dialogue":%s,"shot":%s}
                """.formatted(
                style,
                instruction == null || instruction.isBlank() ? "优化表达，保持剧情不变" : instruction,
                json.quoteJson(current.time()),
                json.quoteJson(current.location()),
                json.toJsonArray(current.characters()),
                json.quoteJson(current.action()),
                json.quoteJson(current.dialogue()),
                json.quoteJson(current.shot())
        );
        JsonNode node = json.callJsonWithRetry(system, user);
        List<LlmGateway.ScriptDto> items = parseScripts(node);
        if (items.isEmpty()) {
            throw new BusinessException("AI 未返回剧本条目");
        }
        return items.get(0);
    }

    List<LlmGateway.ScriptDto> parseScripts(JsonNode node) {
        List<LlmGateway.ScriptDto> result = new ArrayList<>();
        for (JsonNode item : json.itemsNode(node)) {
            result.add(new LlmGateway.ScriptDto(
                    json.text(item, "time", "timeLabel", "时段"),
                    json.text(item, "location", "地点"),
                    json.stringList(item, "characters", "character", "人物"),
                    json.text(item, "action", "动作"),
                    json.text(item, "dialogue", "dialog", "台词"),
                    json.text(item, "shot", "camera", "镜头")
            ));
        }
        return result;
    }
}
