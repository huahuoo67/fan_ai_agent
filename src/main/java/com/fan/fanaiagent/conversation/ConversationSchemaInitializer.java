package com.fan.fanaiagent.conversation;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConversationSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public ConversationSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS public.chat_conversation (
                    id UUID PRIMARY KEY,
                    chat_type VARCHAR(20) NOT NULL,
                    title VARCHAR(100) NOT NULL DEFAULT '新对话',
                    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                    message_count INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_message_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT ck_chat_conversation_type CHECK (chat_type IN ('LOVE', 'MANUS')),
                    CONSTRAINT ck_chat_conversation_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS public.chat_message (
                    id BIGSERIAL PRIMARY KEY,
                    conversation_id UUID NOT NULL REFERENCES public.chat_conversation(id) ON DELETE CASCADE,
                    role VARCHAR(20) NOT NULL,
                    content TEXT NOT NULL DEFAULT '',
                    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
                    visible BOOLEAN NOT NULL DEFAULT TRUE,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    completed_at TIMESTAMPTZ,
                    CONSTRAINT ck_chat_message_role CHECK (role IN ('USER', 'ASSISTANT', 'TOOL')),
                    CONSTRAINT ck_chat_message_status CHECK (status IN ('STREAMING', 'COMPLETED', 'FAILED', 'STOPPED'))
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_chat_conversation_type_time ON public.chat_conversation(chat_type, last_message_at DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_chat_message_conversation ON public.chat_message(conversation_id, id)");
    }
}
