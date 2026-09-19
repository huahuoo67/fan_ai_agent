package com.fan.fanaiagent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fan.fanaiagent.agent.model.AgentEvent;
import com.fan.fanaiagent.agent.model.AgentState;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 处理工具调用的基础代理类。
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    private final ToolCallback[] availableTools;
    private ChatResponse toolCallChatResponse;
    private final ToolCallingManager toolCallingManager;
    private final ChatOptions chatOptions;

    private String finalAnswer;
    private String lastToolSignature;
    private int repeatedToolCallCount;

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public ToolCallAgent(ToolCallback[] availableTools) {
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.chatOptions = DashScopeChatOptions.builder()
                .withInternalToolExecutionEnabled(false)
                .withMultiModel(true)
                .build();
    }

    @Override
    public boolean think() {
        emit(AgentEvent.builder()
                .type("thinking")
                .content(getCurrentStep() == 1 ? "正在分析问题" : "正在整理信息并规划下一步")
                .step(getCurrentStep())
                .status("running")
                .build());

        List<Message> messageList = getMessageList();
        Prompt prompt = new Prompt(messageList, this.chatOptions);
        try {
            String systemInstructions = StrUtil.blankToDefault(getSystemPrompt(), "");
            if (StrUtil.isNotBlank(getNextStepPrompt())) {
                systemInstructions += "\n" + getNextStepPrompt();
            }

            // 工具决策必须使用完整响应。Spring AI 1.0.0 的 MessageAggregator
            // 只聚合文本，不保留流式响应里的 toolCalls，会导致天气查询等任务被误判为完成。
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(systemInstructions)
                    .toolCallbacks(availableTools)
                    .call()
                    .chatResponse();
            if (chatResponse == null || chatResponse.getResult() == null) {
                throw new IllegalStateException("模型未返回有效响应");
            }
            this.toolCallChatResponse = chatResponse;

            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            for (AssistantMessage.ToolCall toolCall : toolCallList) {
                JsonNode args = JSON_MAPPER.readTree(toolCall.arguments());
                if (args == null || !args.isObject()) {
                    throw new IllegalArgumentException("工具参数必须是 JSON 对象");
                }
            }

            log.info("{} selected {} tool(s)", getName(), toolCallList.size());

            if (toolCallList.isEmpty()) {
                // 工具决策结束后单独发起最终回答流。这样既保留可靠的工具调用，
                // 又能把最终文本按模型真实 token 实时推送到浏览器。
                this.finalAnswer = streamFinalAnswer(prompt, systemInstructions);
                getMessageList().add(new AssistantMessage(this.finalAnswer));
                setState(AgentState.FINISHED);
                return false;
            }

            String toolSignature = toolCallList.stream()
                    .map(toolCall -> toolCall.name() + ":" + toolCall.arguments())
                    .collect(Collectors.joining("|"));
            if (toolSignature.equals(lastToolSignature)) {
                repeatedToolCallCount++;
            } else {
                lastToolSignature = toolSignature;
                repeatedToolCallCount = 1;
            }
            if (repeatedToolCallCount >= 3) {
                setState(AgentState.ERROR);
                emitError("REPEATED_TOOL_CALL", "检测到重复操作，任务已自动结束", true);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("模型调用或工具参数解析失败", e);
            throw new IllegalStateException("模型调用或工具参数解析失败", e);
        }
    }

    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具需要调用";
        }

        toolCallChatResponse.getResult().getOutput().getToolCalls().forEach(toolCall ->
                emit(toolEvent(toolCall.name(), "running")));

        Prompt prompt = new Prompt(getMessageList(), this.chatOptions);
        ToolExecutionResult executionResult =
                toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);

        // 即使用户在工具执行期间点击停止，也保留已经得到的工具结果，供下一轮继续。
        setMessageList(executionResult.conversationHistory());
        ToolResponseMessage responseMessage =
                (ToolResponseMessage) CollUtil.getLast(executionResult.conversationHistory());

        if (isStopRequested()) {
            return "任务已停止";
        }

        boolean terminateCalled = responseMessage.getResponses().stream()
                .anyMatch(response -> response.name().equals("doTerminate"));

        responseMessage.getResponses().forEach(response ->
                emit(toolEvent(response.name(), "success")));

        String results = responseMessage.getResponses().stream()
                .map(response -> "工具 " + response.name() + " 返回的结果：" + response.responseData())
                .collect(Collectors.joining("\n"));
        log.info(results);

        if (terminateCalled) {
            finalAnswer = StrUtil.blankToDefault(
                    toolCallChatResponse.getResult().getOutput().getText(),
                    "任务已结束"
            );
            setState(AgentState.FINISHED);
            emitAnswerDelta(finalAnswer);
        }
        return results;
    }

    @Override
    public String step() {
        return think() ? act() : StrUtil.blankToDefault(finalAnswer, "任务执行结束");
    }

    @Override
    protected void resetTurnState() {
        toolCallChatResponse = null;
        finalAnswer = null;
        lastToolSignature = null;
        repeatedToolCallCount = 0;
    }

    private String streamFinalAnswer(Prompt prompt, String systemInstructions) {
        StringBuilder answer = new StringBuilder();
        getChatClient().prompt(prompt)
                .system(systemInstructions + "\nDo not call tools now. Based on the available conversation and tool results, provide the complete final answer directly.")
                .stream()
                .content()
                .doOnNext(chunk -> {
                    if (!isStopRequested() && StrUtil.isNotEmpty(chunk)) {
                        answer.append(chunk);
                        emitAnswerDelta(chunk);
                    }
                })
                .blockLast();
        return StrUtil.blankToDefault(answer.toString(), "任务已完成");
    }

    private void emitAnswerDelta(String text) {
        if (StrUtil.isEmpty(text) || isStopRequested()) {
            return;
        }
        emit(AgentEvent.builder()
                .type("answer")
                .content(text)
                .step(getCurrentStep())
                .status("running")
                .build());
    }

    private AgentEvent toolEvent(String toolName, String status) {
        boolean running = "running".equals(status);
        return AgentEvent.builder()
                .type("tool")
                .content(toolStatusText(toolName, running))
                .toolName(toolName)
                .status(status)
                .step(getCurrentStep())
                .build();
    }

    private String toolStatusText(String toolName, boolean running) {
        if (toolName != null && toolName.endsWith("searchImage")) {
            return running ? "正在搜索图片" : "图片搜索完成";
        }
        return switch (toolName) {
            case "searchWeb" -> running ? "正在搜索相关信息" : "相关信息搜索完成";
            case "scrapeWebPage" -> running ? "正在读取网页内容" : "网页内容读取完成";
            case "readFile" -> running ? "正在读取文件" : "文件读取完成";
            case "writeFile" -> running ? "正在写入文件" : "文件写入完成";
            case "downloadResource" -> running ? "正在下载所需资源" : "资源下载完成";
            case "executeTerminalCommand" -> running ? "正在执行必要操作" : "操作执行完成";
            case "generatePDF" -> running ? "正在生成 PDF 文档" : "PDF 文档生成完成";
            case "doTerminate" -> running ? "正在结束当前任务" : "当前任务已结束";
            default -> running ? "正在调用辅助工具" : "辅助工具执行完成";
        };
    }
}




