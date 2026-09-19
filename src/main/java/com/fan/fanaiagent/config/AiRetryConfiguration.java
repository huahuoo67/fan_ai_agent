package com.fan.fanaiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.retry.support.RetryTemplate;

/**
 * 额度、鉴权等明确错误立即反馈；网络抖动等临时异常进行有限重试。
 */
@Configuration
public class AiRetryConfiguration {

    @Bean
    @Primary
    public RetryTemplate modelRetryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(3)
                .exponentialBackoff(1000, 2, 5000)
                .retryOn(TransientAiException.class)
                .build();
    }
}
