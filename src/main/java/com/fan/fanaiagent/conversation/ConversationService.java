package com.fan.fanaiagent.conversation;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ConversationService {

    private static final int MEMORY_WINDOW = 30;
    private final JdbcTemplate jdbcTemplate;

    public ConversationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ConversationSummary create(ChatType chatType) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO public.chat_conversation(id, chat_type)
                VALUES (?, ?)
                """, id, chatType.name());
        return get(id);
    }

    public ConversationSummary ensure(String conversationId, ChatType chatType) {
        UUID id = parseId(conversationId);
        List<ConversationSummary> found = jdbcTemplate.query("""
                SELECT id, chat_type, title, message_count, created_at, last_message_at
                FROM public.chat_conversation WHERE id = ?
                """, this::mapConversation, id);
        if (found.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO public.chat_conversation(id, chat_type)
                    VALUES (?, ?) ON CONFLICT (id) DO NOTHING
                    """, id, chatType.name());
            return get(id);
        }
        ConversationSummary conversation = found.getFirst();
        if (conversation.chatType() != chatType) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话类型不匹配");
        }
        return conversation;
    }

    public ConversationSummary get(UUID id) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT id, chat_type, title, message_count, created_at, last_message_at
                    FROM public.chat_conversation
                    WHERE id = ? AND status = 'ACTIVE'
                    """, this::mapConversation, id);
        } catch (EmptyResultDataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
    }

    public List<ConversationSummary> list(ChatType chatType) {
        return jdbcTemplate.query("""
                SELECT id, chat_type, title, message_count, created_at, last_message_at
                FROM public.chat_conversation
                WHERE chat_type = ? AND status = 'ACTIVE'
                ORDER BY last_message_at DESC
                LIMIT 200
                """, this::mapConversation, chatType.name());
    }

    public List<StoredChatMessage> listMessages(UUID conversationId) {
        get(conversationId);
        return jdbcTemplate.query("""
                SELECT id, role, content, status, created_at
                FROM public.chat_message
                WHERE conversation_id = ? AND visible = TRUE
                ORDER BY id
                """, this::mapMessage, conversationId);
    }

    public List<Message> loadMemory(String conversationId) {
        UUID id = parseId(conversationId);
        List<Message> messages = jdbcTemplate.query("""
                SELECT role, content
                FROM public.chat_message
                WHERE conversation_id = ?
                  AND status = 'COMPLETED'
                  AND role IN ('USER', 'ASSISTANT')
                ORDER BY id DESC
                LIMIT ?
                """, (rs, rowNum) -> "USER".equals(rs.getString("role"))
                    ? new UserMessage(rs.getString("content"))
                    : new AssistantMessage(rs.getString("content")), id, MEMORY_WINDOW);
        Collections.reverse(messages);
        return new ArrayList<>(messages);
    }

    @Transactional
    public void appendMessages(String conversationId, List<Message> messages) {
        UUID id = parseId(conversationId);
        for (Message message : messages) {
            if (message instanceof UserMessage) {
                append(id, "USER", message.getText(), "COMPLETED");
            } else if (message instanceof AssistantMessage) {
                append(id, "ASSISTANT", message.getText(), "COMPLETED");
            }
        }
    }

    @Transactional
    public long append(UUID conversationId, String role, String content, String status) {
        get(conversationId);
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO public.chat_message(conversation_id, role, content, status, completed_at)
                VALUES (?, ?, ?, ?, CASE WHEN ? = 'COMPLETED' THEN CURRENT_TIMESTAMP ELSE NULL END)
                RETURNING id
                """, Long.class, conversationId, role, content, status, status);
        jdbcTemplate.update("""
                UPDATE public.chat_conversation
                SET message_count = message_count + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    last_message_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, conversationId);
        if ("USER".equals(role)) {
            setTitleFromFirstMessage(conversationId, content);
        }
        return id == null ? -1 : id;
    }

    public void rename(UUID id, String title) {
        if (!StringUtils.hasText(title)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话标题为空");
        }
        int updated = jdbcTemplate.update("""
                UPDATE public.chat_conversation
                SET title = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'ACTIVE'
                """, compact(title, 50), id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
    }

    public void delete(UUID id) {
        int deleted = jdbcTemplate.update("DELETE FROM public.chat_conversation WHERE id = ?", id);
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
    }

    public void clearMessages(String conversationId) {
        UUID id = parseId(conversationId);
        jdbcTemplate.update("DELETE FROM public.chat_message WHERE conversation_id = ?", id);
        jdbcTemplate.update("""
                UPDATE public.chat_conversation
                SET message_count = 0, title = '新对话', updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, id);
    }

    public UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId 格式错误");
        }
    }

    private void setTitleFromFirstMessage(UUID conversationId, String content) {
        String title = summarizeFirstMessage(content);
        jdbcTemplate.update("""
                UPDATE public.chat_conversation
                SET title = ?
                WHERE id = ? AND title = '新对话'
                """, title, conversationId);
    }

    private String summarizeFirstMessage(String content) {
        String original = StringUtils.hasText(content) ? content.replaceAll("\\s+", " ").trim() : "新对话";
        String value = original;
        value = value.replaceFirst("^(你好|您好|请问|我想问一下|我想咨询一下|帮我|麻烦你)[，,：:。.!！?？\\s]*", "");
        if (!StringUtils.hasText(value)) {
            value = original;
        }
        String[] parts = value.split("[。！？!?；;\\n]", 2);
        String firstSentence = StringUtils.hasText(parts[0]) ? parts[0] : value;
        return compact(firstSentence, 18);
    }

    private String compact(String value, int maxLength) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }

    private ConversationSummary mapConversation(ResultSet rs, int rowNum) throws SQLException {
        return new ConversationSummary(
                rs.getObject("id", UUID.class),
                ChatType.valueOf(rs.getString("chat_type").toUpperCase(Locale.ROOT)),
                rs.getString("title"),
                rs.getInt("message_count"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("last_message_at", OffsetDateTime.class)
        );
    }

    private StoredChatMessage mapMessage(ResultSet rs, int rowNum) throws SQLException {
        return new StoredChatMessage(
                rs.getLong("id"),
                rs.getString("role"),
                rs.getString("content"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }
}
