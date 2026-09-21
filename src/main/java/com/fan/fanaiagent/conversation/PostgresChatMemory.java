package com.fan.fanaiagent.conversation;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PostgresChatMemory implements ChatMemory {

    private final ConversationService conversationService;

    public PostgresChatMemory(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        conversationService.appendMessages(conversationId, messages);
    }

    @Override
    public List<Message> get(String conversationId) {
        return conversationService.loadMemory(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        conversationService.clearMessages(conversationId);
    }
}
