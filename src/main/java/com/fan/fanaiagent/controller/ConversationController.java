package com.fan.fanaiagent.controller;

import com.fan.fanaiagent.conversation.ChatType;
import com.fan.fanaiagent.conversation.ConversationService;
import com.fan.fanaiagent.conversation.ConversationSummary;
import com.fan.fanaiagent.conversation.StoredChatMessage;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationSummary create(@RequestBody Map<String, String> body) {
        return conversationService.create(parseType(body.get("chatType")));
    }

    @GetMapping
    public List<ConversationSummary> list(@RequestParam String chatType) {
        return conversationService.list(parseType(chatType));
    }

    @GetMapping("/{id}/messages")
    public List<StoredChatMessage> messages(@PathVariable UUID id) {
        return conversationService.listMessages(id);
    }

    @PatchMapping("/{id}")
    public ConversationSummary rename(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        conversationService.rename(id, body.get("title"));
        return conversationService.get(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        conversationService.delete(id);
    }

    private ChatType parseType(String value) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "chatType 不能为空");
        }
        try {
            return ChatType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "chatType 仅支持 LOVE 或 MANUS");
        }
    }
}
