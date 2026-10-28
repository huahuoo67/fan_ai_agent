package com.fan.fanaiagent.platform;

import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import reactor.core.publisher.Flux;
import java.util.*;
import java.util.function.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.fan.fanaiagent.platform.PlatformTypes.*;

class PlatformModelTest {
    ChatModel gateway;PlatformStore store;ArtifactService files;LoveKnowledge knowledge;PublicHttp http;PlatformModel model;
    @BeforeEach void setup() {
        gateway=mock(ChatModel.class);store=mock(PlatformStore.class);files=mock(ArtifactService.class);
        knowledge=mock(LoveKnowledge.class);http=mock(PublicHttp.class);
        model=new PlatformModel(gateway,knowledge,store,files,http);
        ReflectionTestUtils.setField(model,"maxSteps",8);ReflectionTestUtils.setField(model,"searchKey","");
        when(store.active(any())).thenReturn(true);when(knowledge.retrieve(anyString())).thenReturn("");
        when(gateway.stream(any(Prompt.class))).thenReturn(Flux.just(response("模型回答")));
    }
    ChatResponse response(String text) {return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));}
    ExecutionContext ctx(Capabilities cap) {return new ExecutionContext(1,UUID.randomUUID(),UUID.randomUUID(),cap);}
    TurnRequest request(String text,Capabilities cap) {return new TurnRequest(UUID.randomUUID(),text,cap,null,null,null);}
    @Test void plainConversationHasNoToolsRagOrImplicitProviderSearch() {
        var c=ctx(Capabilities.NONE);var out=new StringBuilder();
        model.answer(c,request("你好",c.capabilities()),List.of(),new EffectiveMemory(List.of(),List.of()),out::append,(t,d)->{});
        verifyNoInteractions(knowledge,http,files);verify(gateway,never()).call(any(Prompt.class));
        var prompt=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(gateway).stream(prompt.capture());
        var options=(DashScopeChatOptions)prompt.getValue().getOptions();
        assertEquals(false,options.getEnableSearch());assertTrue(options.getToolCallbacks().isEmpty());assertEquals("模型回答",out.toString());
    }
    @Test void personalFactsAndRetentionBypassPublicKnowledgeAndModel() {
        var c=ctx(new Capabilities(true,true,true));
        model.answer(c,request("说出关于我的信息",c.capabilities()),List.of(),new EffectiveMemory(List.of(),List.of()),s->{assertFalse(s.contains("香菜"));},(t,d)->{});
        verify(gateway,never()).call(any(Prompt.class));verify(gateway,never()).stream(any(Prompt.class));
        verifyNoInteractions(knowledge,http,files);
    }
    @Test void toolManagerExecutesOnlyScopedCallbacksAndWorkerGetsNoLoveNarrative() throws Exception {
        var c=ctx(new Capabilities(true,true,false));
        var tool=new AssistantMessage("",Map.of(),List.of(new AssistantMessage.ToolCall("call1","function","createText","{\"name\":\"plan.txt\",\"content\":\"公开约会计划\"}")));
        when(gateway.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(tool))),response("已生成"));
        when(files.save(eq(c),eq("plan.txt"),anyString(),any(byte[].class))).thenReturn(Map.of("id",UUID.randomUUID(),"name","plan.txt"));
        var events=new ArrayList<String>();
        model.answer(c,request("我和女朋友发生了非常私密的争吵，请生成约会计划文件",c.capabilities()),
            List.of(Map.of("role","USER","content","只在情感上下文出现的秘密")),new EffectiveMemory(List.of(),List.of()),s->{},(t,d)->events.add(t));
        verify(files).save(eq(c),eq("plan.txt"),anyString(),any(byte[].class));assertTrue(events.contains("artifact"));verifyNoInteractions(http);
        var prompt=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(gateway,times(2)).call(prompt.capture());
        for(var p:prompt.getAllValues()) {assertFalse(p.getContents().contains("私密的争吵"));assertFalse(p.getContents().contains("只在情感上下文"));}
    }
    @Test void emptyProviderStreamIsNotACompletedAnswer() {
        when(gateway.stream(any(Prompt.class))).thenReturn(Flux.empty());var c=ctx(Capabilities.NONE);
        assertThrows(IllegalStateException.class,()->model.answer(c,request("你好",c.capabilities()),List.of(),new EffectiveMemory(List.of(),List.of()),s->{},(t,d)->{}));
    }
    @Test void configuredMultimodalRouteSurvivesPerTurnOptionsForChatAndTools() {
        ReflectionTestUtils.setField(model,"multiModel",true);
        var c=ctx(new Capabilities(false,true,false));
        when(gateway.call(any(Prompt.class))).thenReturn(response("任务完成"));
        model.answer(c,request("生成计划文件",c.capabilities()),List.of(),new EffectiveMemory(List.of(),List.of()),s->{},(t,d)->{});
        var prompt=org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(gateway).call(prompt.capture());assertEquals(true,((DashScopeChatOptions)prompt.getValue().getOptions()).getMultiModel());
        verify(gateway).stream(prompt.capture());assertEquals(true,((DashScopeChatOptions)prompt.getValue().getOptions()).getMultiModel());
    }
    @Test void pdfInMainInputEntersSemanticPlannerWithoutSupplementAndKeepsSubject() {
        var c=ctx(new Capabilities(false,true,false));
        when(gateway.call(any(Prompt.class))).thenReturn(response("规划完成"));
        model.answer(c,request("关于钟楼的介绍，pdf格式",c.capabilities()),
            List.of(Map.of("role","ASSISTANT","content","FanAI 简介.pdf"),Map.of("role","USER","content","介绍西安钟楼")),
            new EffectiveMemory(List.of(),List.of()),s->{},(t,d)->{});
        var p=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(gateway).call(p.capture());
        assertTrue(p.getValue().getContents().contains("关于钟楼的介绍，pdf格式"));
        assertTrue(p.getValue().getContents().contains("介绍西安钟楼"));
        assertTrue(p.getValue().getContents().indexOf("介绍西安钟楼")<p.getValue().getContents().indexOf("关于钟楼的介绍，pdf格式"));
        assertFalse(p.getValue().getContents().contains("FanAI 简介.pdf"));
        assertTrue(((DashScopeChatOptions)p.getValue().getOptions()).getToolCallbacks().stream().anyMatch(t->"createPdf".equals(t.getToolDefinition().name())));
    }
    @Test void webSwitchRunsSearchWithoutKeywordsOrModelChoosingSearch() throws Exception {
        ReflectionTestUtils.setField(model,"searchKey","fixture-search-key");
        when(http.get(anyString(),anyInt())).thenReturn(new PublicHttp.Result("{\"organic_results\":[]}".getBytes(),"application/json","https://example.com"));
        when(gateway.call(any(Prompt.class))).thenReturn(response("规划完成"));
        var c=ctx(new Capabilities(false,false,true));
        model.answer(c,request("钟楼的建筑特点",c.capabilities()),List.of(),new EffectiveMemory(List.of(),List.of()),s->{},(t,d)->{});
        var url=org.mockito.ArgumentCaptor.forClass(String.class);verify(http).get(url.capture(),anyInt());
        assertTrue(java.net.URLDecoder.decode(url.getValue(),java.nio.charset.StandardCharsets.UTF_8).contains("钟楼的建筑特点"));
        verify(gateway,never()).call(any(Prompt.class));
    }
    @Test void supplementDoesNotReplaceMainTaskSubject() {
        when(gateway.call(any(Prompt.class))).thenReturn(response("规划完成"));
        var c=ctx(new Capabilities(false,true,false));
        model.answer(c,new TurnRequest(UUID.randomUUID(),"制作钟楼介绍PDF",c.capabilities(),null,null,"两百字以内"),List.of(),new EffectiveMemory(List.of(),List.of()),s->{},(t,d)->{});
        var p=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(gateway).call(p.capture());
        assertTrue(p.getValue().getContents().contains("制作钟楼介绍PDF"));assertTrue(p.getValue().getContents().contains("两百字以内"));
    }
    @Test void combinedPublicSearchAndPdfUseOnlyApprovedQueryAndMinimalWorkerInput() throws Exception {
        var c=ctx(new Capabilities(true,true,true));ReflectionTestUtils.setField(model,"searchKey","fixture-search-key");
        when(http.get(anyString(),anyInt())).thenReturn(new PublicHttp.Result(
            "{\"organic_results\":[{\"title\":\"公开公园信息\",\"link\":\"https://example.com/park\",\"snippet\":\"开放安排\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8),"application/json","https://example.com/park"));
        var search=new AssistantMessage("",Map.of(),List.of(new AssistantMessage.ToolCall("s","function","searchPublic","{}")));
        var pdf=new AssistantMessage("",Map.of(),List.of(new AssistantMessage.ToolCall("p","function","createPdf","{\"name\":\"plan.pdf\",\"content\":\"清淡约会安排\"}")));
        when(gateway.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(search))),new ChatResponse(List.of(new Generation(pdf))),response("已有计划文件"));
        when(files.save(eq(c),eq("plan.pdf"),anyString(),any(byte[].class))).thenReturn(Map.of("id",UUID.randomUUID(),"name","plan.pdf"));
        var events=new ArrayList<String>();
        model.answer(c,request("我和女朋友有私密矛盾，联系13800000000，请查今天上海公开公园信息并生成约会计划PDF",c.capabilities()),
            List.of(),new EffectiveMemory(List.of(),List.of()),s->{},(t,d)->events.add(t));
        assertTrue(events.containsAll(List.of("sources","artifact")));
        var url=org.mockito.ArgumentCaptor.forClass(String.class);verify(http).get(url.capture(),anyInt());
        assertFalse(url.getValue().contains("13800000000"));
        var prompt=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(gateway,times(3)).call(prompt.capture());
        for(var p:prompt.getAllValues()) {assertFalse(p.getContents().contains("私密矛盾"));assertFalse(p.getContents().contains("13800000000"));}
    }
}
