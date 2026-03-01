package com.shortdrama.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient 配置。
 *
 * <p>Spring AI 统一入口：后续的意图识别、RAG 问答、函数调用都在此 ChatClient 之上
 * 叠加 Advisor（记忆 / RAG / 成本统计），避免每处各自 new client。
 */
@Configuration
public class ChatConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
