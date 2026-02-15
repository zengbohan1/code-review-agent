package com.shortdrama.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 短剧订阅智能客服 Agent 启动类。
 *
 * <p>技术栈：Java 17 + Spring Boot 3.5 + Spring AI 1.1（ChatClient / @Tool / Advisor / Streaming）。
 * LLM 通过 OpenAI 兼容层接入 DeepSeek；订单/支付系统为基于真实接口语义自建的 Mock。
 */
@SpringBootApplication
public class ShortDramaAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShortDramaAgentApplication.class, args);
    }
}
