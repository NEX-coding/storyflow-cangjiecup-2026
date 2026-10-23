package com.storyflow.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyflow.ai.llm.LlmJsonSupport;
import com.storyflow.common.BusinessException;
import com.storyflow.config.StoryflowProperties;
import com.storyflow.repository.PromptTemplateRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssetExtractionJsonTest {
    private final OllamaClient client = mock(OllamaClient.class);
    private final StoryflowProperties properties = new StoryflowProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptCatalog catalog = new PromptCatalog(mock(PromptTemplateRepository.class));

    private LlmGateway gateway() {
        properties.getOllama().setMaxRetries(2);
        return new LlmGateway(client, null, catalog, new PromptPipeline(catalog), null, mapper, properties);
    }

    @Test
    void extractsBothCharactersUsingObjectModeContract() {
        when(client.chatJson(anyString(), anyString())).thenAnswer(invocation -> {
            String user = invocation.getArgument(1);
            if (user.contains("根据上面的信息")) {
                assertTrue(user.contains("{\"items\":[...]}"));
                return "{\"items\":[{\"name\":\"林夏\",\"description\":\"大学生\"},"
                        + "{\"name\":\"陈叔\",\"description\":\"管理员\"}]}";
            }
            throw new BusinessException("生图提示词服务暂不可用");
        });
        var assets = gateway().extractAssetsOfType("character", "林夏向陈叔借书", "[]", "电影胶片");
        assertEquals(java.util.List.of("林夏", "陈叔"), assets.stream().map(LlmGateway.AssetDto::name).toList());
        assertTrue(assets.stream().allMatch(a -> !a.imagePrompt().isBlank()));
    }

    @Test
    void bareAssetObjectRetriesAndFailsInsteadOfReportingZero() {
        when(client.chatJson(anyString(), anyString()))
                .thenReturn("{\"name\":\"林夏\",\"description\":\"大学生\"}");
        assertThrows(BusinessException.class,
                () -> gateway().extractAssetsOfType("character", "林夏向陈叔借书", "[]", "电影胶片"));
        verify(client, times(2)).chatJson(anyString(), anyString());
    }

    @Test
    void explicitEmptyListRemainsValidForAbsentProps() {
        when(client.chatJson(anyString(), anyString())).thenReturn("{\"items\":[]}");
        assertTrue(gateway().extractAssetsOfType("prop", "无人空镜", "[]", "电影胶片").isEmpty());
        verify(client).chatJson(anyString(), anyString());
    }

    @Test
    void sharedJsonParserRejectsUnknownAndNullButKeepsExplicitEmptyLists() throws Exception {
        LlmJsonSupport support = new LlmJsonSupport(client, catalog, mapper, properties);
        assertFalse(support.itemsNode(mapper.readTree("{\"name\":\"林夏\"}")).isArray());
        assertFalse(support.itemsNode(mapper.nullNode()).isArray());
        assertTrue(support.itemsNode(mapper.readTree("{\"items\":[]}")).isArray());
    }
}
