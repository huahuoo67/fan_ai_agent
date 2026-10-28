package com.fan.fanaiagent.platform;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;

public final class PlatformTypes {
    private PlatformTypes() {}
    public record Capabilities(boolean love, boolean task, boolean web) {
        public static final Capabilities NONE = new Capabilities(false, false, false);
        public String scope() { return love ? "LOVE" : "GENERAL"; }
        public boolean allows(String scope) { return !"LOVE".equals(scope) || love; }
    }
    public record TurnRequest(@NotNull UUID requestId, @NotBlank @Size(max=12000) String message,
                              @NotNull Capabilities capabilities, @Size(max=8) List<@NotNull UUID> contextGrants,
                              @Size(max=200) String reviewedSearchQuery, @Size(max=6000) String taskInput) {}
    public record ExecutionContext(long userId, UUID conversationId, UUID runId, Capabilities capabilities) {}
    public record MemoryFact(UUID id, String subject, String key, String value, String kind, String scope,
                             boolean sensitive, OffsetDateTime expiresAt, String status, int version,
                             long sourceMessageId, UUID sourceConversationId, OffsetDateTime createdAt,String sourceType) {
        public MemoryFact(UUID id,String subject,String key,String value,String kind,String scope,boolean sensitive,
                OffsetDateTime expiresAt,String status,int version,long sourceMessageId,UUID sourceConversationId,OffsetDateTime createdAt) {
            this(id,subject,key,value,kind,scope,sensitive,expiresAt,status,version,sourceMessageId,sourceConversationId,createdAt,"MESSAGE");
        }
    }
    public record EffectiveMemory(List<MemoryFact> facts, List<String> notices) {}
    public record MemorySnapshot(List<MemoryFact> facts, List<String> notices) {}
    public record Account(String id, String account, String username, String avatarUrl, Integer gender,
                          String phone, String email, String role, boolean enabled, boolean memoryEnabled) implements java.security.Principal,java.io.Serializable {
        @Override @com.fasterxml.jackson.annotation.JsonIgnore public String getName() {return id;}
    }
    public record Register(@Pattern(regexp="[a-zA-Z0-9_]{4,18}") @NotNull String account,
                           @NotNull @Size(min=8,max=72) String password,
                           @NotBlank @Size(max=50) String username) {}
    public record Login(@NotBlank @Size(max=18) String account, @NotNull @Size(max=72) String password) {}
    public record Profile(@NotBlank @Size(max=50) String username, @Size(max=1024) String avatarUrl,
                          @Min(0) @Max(2) Integer gender, @Size(max=128) String phone,
                          @Email @Size(max=512) String email, boolean memoryEnabled) {}
}
