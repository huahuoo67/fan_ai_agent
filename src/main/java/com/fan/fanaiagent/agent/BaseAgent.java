package com.fan.fanaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.fan.fanaiagent.agent.model.AgentEvent;
import com.fan.fanaiagent.agent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 抽象基础代理类，用于管理代理状态、会话记忆和执行流程。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    private String name;
    private String systemPrompt;
    private String nextStepPrompt;
    private AgentState state = AgentState.IDLE;
    private int currentStep = 0;
    private int maxSteps = 10;
    private ChatClient chatClient;

    /**
     * 同一个 Agent 实例会被一个 chatId 复用，这里的消息就是该会话的上下文。
     */
    private List<Message> messageList = new ArrayList<>();

    private transient volatile SseEmitter streamEmitter;
    private volatile boolean executing;
    private volatile boolean stopRequested;
    private volatile boolean clientConnected;

    public String run(String userPrompt) {
        if (executing) {
            throw new IllegalStateException("Agent is already running");
        }
        if (StrUtil.isBlank(userPrompt)) {
            throw new IllegalArgumentException("User prompt is empty");
        }

        state = AgentState.RUNNING;
        currentStep = 0;
        resetTurnState();
        messageList.add(new UserMessage(userPrompt));
        List<String> results = new ArrayList<>();

        try {
            for (int i = 0; i < maxSteps && state == AgentState.RUNNING; i++) {
                currentStep = i + 1;
                results.add("Step " + currentStep + ": " + step());
            }
            if (currentStep >= maxSteps && state == AgentState.RUNNING) {
                state = AgentState.ERROR;
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
            return String.join("\n", results);
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("error executing agent", e);
            return "执行错误：" + e.getMessage();
        } finally {
            cleanup();
        }
    }

    public synchronized SseEmitter runStream(String userPrompt) {
        if (executing) {
            throw new IllegalStateException("当前会话已有任务正在执行");
        }
        if (StrUtil.isBlank(userPrompt)) {
            throw new IllegalArgumentException("消息内容为空");
        }

        SseEmitter emitter = new SseEmitter(300000L);
        streamEmitter = emitter;
        executing = true;
        stopRequested = false;
        clientConnected = true;
        state = AgentState.RUNNING;
        currentStep = 0;
        resetTurnState();

        CompletableFuture.runAsync(() -> executeStream(userPrompt, emitter));

        emitter.onTimeout(() -> {
            log.info("SSE connection timeout, agent={}", name);
            markClientDisconnected();
        });
        emitter.onError(error -> {
            log.info("SSE connection closed, agent={}, reason={}", name, error.getMessage());
            markClientDisconnected();
        });
        emitter.onCompletion(() -> {
            if (executing && state == AgentState.RUNNING) {
                markClientDisconnected();
            }
            log.info("SSE connection completed, agent={}, state={}", name, state);
        });
        return emitter;
    }

    private void executeStream(String userPrompt, SseEmitter emitter) {
        try {
            emit(AgentEvent.builder()
                    .type("thinking")
                    .content("正在理解你的需求")
                    .step(0)
                    .status("running")
                    .build());
            messageList.add(new UserMessage(userPrompt));

            for (int i = 0; i < maxSteps && state == AgentState.RUNNING && !stopRequested; i++) {
                currentStep = i + 1;
                log.info("Executing step {}/{}, agent={}", currentStep, maxSteps, name);
                step();
            }

            if (stopRequested || state == AgentState.STOPPED) {
                state = AgentState.STOPPED;
                emit(AgentEvent.builder()
                        .type("stopped")
                        .content("任务已停止，可以继续当前会话")
                        .status("stopped")
                        .step(currentStep)
                        .build());
                emitDone(false, "stopped");
            } else if (currentStep >= maxSteps && state == AgentState.RUNNING) {
                state = AgentState.ERROR;
                emitError("MAX_STEPS_EXCEEDED", "任务执行步骤过多，已自动停止", true);
                emitDone(false, "failed");
            } else {
                if (state == AgentState.RUNNING) {
                    state = AgentState.FINISHED;
                }
                emitDone(state == AgentState.FINISHED,
                        state == AgentState.FINISHED ? "success" : "failed");
            }

            completeQuietly(emitter);
        } catch (Exception e) {
            if (stopRequested || !clientConnected) {
                state = AgentState.STOPPED;
                log.info("Agent stopped after SSE client disconnected, agent={}", name);
            } else {
                state = AgentState.ERROR;
                log.error("error executing agent", e);
                emitError("AGENT_EXECUTION_FAILED", readableMessage(e), true);
                emitDone(false, "failed");
            }
            completeQuietly(emitter);
        } finally {
            executing = false;
            streamEmitter = null;
            cleanup();
        }
    }

    /**
     * 请求停止当前任务。工具调用是同步的，因此会在当前工具返回后结束循环。
     */
    public void requestStop() {
        if (executing) {
            stopRequested = true;
            state = AgentState.STOPPED;
        }
    }

    protected boolean emit(AgentEvent event) {
        SseEmitter emitter = streamEmitter;
        if (emitter == null || !clientConnected) {
            return false;
        }
        if (stopRequested
                && !"stopped".equals(event.getType())
                && !"done".equals(event.getType())
                && !"error".equals(event.getType())) {
            return false;
        }

        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            log.info("SSE client disconnected, stop sending events, agent={}", name);
            markClientDisconnected();
            return false;
        }
    }

    protected void emitError(String code, String content, boolean retryable) {
        emit(AgentEvent.builder()
                .type("error")
                .code(code)
                .content(content)
                .retryable(retryable)
                .status("failed")
                .step(currentStep)
                .build());
    }

    private void emitDone(boolean success, String status) {
        emit(AgentEvent.builder()
                .type("done")
                .content(success ? "任务执行完成" : "任务执行结束")
                .success(success)
                .status(status)
                .step(currentStep)
                .build());
    }

    private void markClientDisconnected() {
        clientConnected = false;
        stopRequested = true;
        if (state == AgentState.RUNNING) {
            state = AgentState.STOPPED;
        }
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            log.debug("SSE emitter already completed, agent={}", name);
        }
    }

    private String readableMessage(Exception e) {
        return StrUtil.isBlank(e.getMessage()) ? "智能体执行过程中发生异常" : e.getMessage();
    }

    /**
     * 开始新一轮时清理单轮状态，保留 messageList 会话上下文。
     */
    protected void resetTurnState() {
    }

    public abstract String step();

    protected void cleanup() {
    }
}
