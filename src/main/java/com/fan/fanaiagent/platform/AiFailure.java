package com.fan.fanaiagent.platform;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Public errors use fixed messages, never raw provider bodies or credentials. */
final class AiFailure {
    record Detail(String code, String message, boolean retryable) {}
    static Detail describe(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            String text = String.valueOf(cause.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (text.contains("freetieronly") || text.contains("quota") || text.contains("insufficient_balance"))
                return new Detail("MODEL_QUOTA", "模型服务额度已耗尽，请联系管理员检查额度及计费设置。", false);
            if (text.contains("invalidapikey") || text.contains("invalid_api_key") || text.contains("http 401"))
                return new Detail("MODEL_AUTH", "模型服务鉴权失败，请联系管理员检查 API 密钥。", false);
            if (text.contains("url error") || text.contains("invalidparameter") || text.contains("modelnotfound"))
                return new Detail("MODEL_CONFIG", "模型接口或参数配置有误，请联系管理员检查模型名称和接口类型。", false);
            if (text.contains("http 429") || text.contains("throttl") || text.contains("rate limit"))
                return new Detail("MODEL_RATE_LIMIT", "模型服务请求较多，请稍后再试。", true);
        }
        return new Detail("AI_SERVICE_ERROR", "AI 服务暂时异常；已完成成果保留，请稍后重试或联系管理员。", true);
    }
}
