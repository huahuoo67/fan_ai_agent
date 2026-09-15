package com.fan.fanaiagent.controller;

import com.fan.fanaiagent.agent.FanManus;
import com.fan.fanaiagent.app.LoveApp;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private LoveApp loveApp;
    @Resource
    private ToolCallback[] allTools;
    @Resource
    private ChatModel dashscopeChatModel;

    /**
     * 一个 chatId 对应一个 FanManus，通过复用实例保留内存中的消息上下文。
     */
    private final ConcurrentMap<String, FanManus> manusSessions = new ConcurrentHashMap<>();

    @GetMapping("/love_app/chat/sync")
    public String doChatWithLoveAppSync(String message, String chatId) {
        return loveApp.doChat(message, chatId);
    }

    @GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppSSE(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId);
    }

    @GetMapping(value = "/love_app/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> doChatWithLoveAppServerSentEvent(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId)
                .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build());
    }

    @GetMapping(value = "/love_app/chat/sse_emitter")
    public SseEmitter doChatWithLoveAppServerSseEmitter(String message, String chatId) {
        SseEmitter emitter = new SseEmitter(180000L);
        loveApp.doChatByStream(message, chatId)
                .subscribe(chunk -> {
                    try {
                        emitter.send(chunk);
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                }, emitter::completeWithError, emitter::complete);
        return emitter;
    }

    @GetMapping(value = "/manus/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter doChatWithManus(String message, String chatId, HttpServletResponse response) {
        validateManusRequest(message, chatId);
        // 禁止浏览器、Servlet 容器前的代理层缓冲 SSE，确保每个 token 立即到达前端。
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
        response.setCharacterEncoding("UTF-8");
        FanManus agent = manusSessions.computeIfAbsent(
                chatId,
                ignored -> new FanManus(allTools, dashscopeChatModel)
        );
        try {
            return agent.runStream(message);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/manus/chat/stop")
    public Map<String, Object> stopManus(String chatId) {
        if (!StringUtils.hasText(chatId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "chatId 不能为空");
        }
        FanManus agent = manusSessions.get(chatId);
        if (agent == null || !agent.isExecuting()) {
            return Map.of("success", true, "status", "idle");
        }
        agent.requestStop();
        return Map.of("success", true, "status", "stopping");
    }

    private void validateManusRequest(String message, String chatId) {
        if (!StringUtils.hasText(message)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息内容为空");
        }
        if (!StringUtils.hasText(chatId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "chatId 不能为空");
        }
    }
}

