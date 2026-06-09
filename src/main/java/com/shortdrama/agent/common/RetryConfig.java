package com.shortdrama.agent.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * LLM 调用重试策略：网络抖动/限流导致的瞬时故障最多重试 2 次，
 * 间隔 1s 固定退避；重试仍失败则由上层降级为规则兜底 + 转人工。
 */
@Configuration
public class RetryConfig {

    @Bean
    RetryTemplate retryTemplate() {
        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(new SimpleRetryPolicy(3));        // 1 次 + 2 次重试
        FixedBackOffPolicy backoff = new FixedBackOffPolicy();
        backoff.setBackOffPeriod(1000L);
        template.setBackOffPolicy(backoff);
        return template;
    }
}
