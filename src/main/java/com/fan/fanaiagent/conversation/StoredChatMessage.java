package com.fan.fanaiagent.conversation;

import java.time.OffsetDateTime;

public record StoredChatMessage(
        long id,
        String role,
        String content,
        String status,
        OffsetDateTime createdAt
) {
}
