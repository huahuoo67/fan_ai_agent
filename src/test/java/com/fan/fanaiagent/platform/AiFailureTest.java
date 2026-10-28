package com.fan.fanaiagent.platform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiFailureTest {
    @Test void quotaHasActionableNonRetryableMessage() {
        var error=AiFailure.describe(new RuntimeException(new IllegalStateException("HTTP 403 AllocationQuota.FreeTierOnly secret-key")));
        assertEquals("MODEL_QUOTA",error.code());assertFalse(error.retryable());
        assertTrue(error.message().contains("额度"));assertFalse(error.message().contains("secret-key"));
    }
    @Test void endpointErrorIdentifiesConfiguration() {
        assertEquals("MODEL_CONFIG",AiFailure.describe(new RuntimeException("HTTP 400 InvalidParameter url error")).code());
    }
    @Test void authErrorDoesNotExposeCredentials() {
        var error=AiFailure.describe(new RuntimeException("InvalidApiKey Bearer secret-token"));
        assertEquals("MODEL_AUTH",error.code());assertFalse(error.message().contains("secret-token"));
    }
    @Test void transientRateLimitIsRetryable() {
        var error=AiFailure.describe(new RuntimeException("HTTP 429"));assertEquals("MODEL_RATE_LIMIT",error.code());assertTrue(error.retryable());
    }
    @Test void unknownErrorNeverLeaksRawBody() {
        var error=AiFailure.describe(new RuntimeException("user private prompt and secret credentials"));
        assertEquals("AI_SERVICE_ERROR",error.code());assertFalse(error.message().contains("private"));
    }
}
