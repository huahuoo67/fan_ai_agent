package com.fan.fanaiagent.platform;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.*;
class SearchContextTest {
    ChatModel model=mock(ChatModel.class);
    SearchQueryPlanner planner=new SearchQueryPlanner(model);
    void reply(String json) {when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(json)))));}
    @Test void completeQuestionPreservesAllConstraintsWithoutAddingTime() {
        reply("{\"query\":\"西安雁塔区天气如何？\",\"question\":\"\"}");
        assertEquals("西安雁塔区天气如何？",planner.plan("西安雁塔区天气如何？",List.of()));
    }
    @Test void generalFollowupUsesRecentUserMessagesNotAssistantGuesses() {
        reply("{\"query\":\"如何安装Java 21？\"}");
        assertEquals("如何安装Java 21？",planner.plan("继续查",List.of(Map.of("role","USER","content","如何安装Java 21？"),Map.of("role","ASSISTANT","content","错误的招聘内容"))));
        var p=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(model).call(p.capture());
        assertTrue(p.getValue().getContents().contains("如何安装Java 21"));assertFalse(p.getValue().getContents().contains("错误的招聘内容"));
    }
    @Test void ambiguityAsksInsteadOfSearching() {
        reply("{\"query\":\"\",\"question\":\"你指的是哪一款产品？\"}");
        assertThrows(SearchQueryPlanner.Clarification.class,()->planner.plan("它多少钱？",List.of()));
    }
    @Test void malformedOutputNeverFallsBackToMechanicalKeywords() {
        reply("not json");assertThrows(SearchQueryPlanner.Clarification.class,()->planner.plan("继续查",List.of()));
    }
    @Test void credentialsAreRedactedBeforePlanning() {
        assertFalse(SearchQueryPlanner.redact("token=secret_value 联系13800000000").contains("secret_value"));
        assertFalse(SearchQueryPlanner.redact("联系13800000000").contains("13800000000"));
    }
    @Test void explicitNewTopicIsNotOverriddenByWeatherRule() {
        reply("{\"query\":\"钟楼的历史是什么？\"}");
        assertEquals("钟楼的历史是什么？",planner.plan("钟楼的历史是什么？",List.of(Map.of("role","USER","content","西安天气如何？"))));
    }
}
