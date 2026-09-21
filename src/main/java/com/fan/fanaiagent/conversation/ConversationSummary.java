package com.fan.fanaiagent.conversation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ConversationSummary(
        UUID id,
        ChatType chatType,
        String title,
        int messageCount,
        OffsetDateTime createdAt,
        OffsetDateTime lastMessageAt
) {
}
