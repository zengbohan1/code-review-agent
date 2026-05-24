package com.shortdrama.agent.config;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话记忆装配：窗口记忆（保留最近 10 条）+ 记忆 Advisor。
 *
 * <p>MessageChatMemoryAdvisor 按 conversationId（这里复用 sessionId）存取对话历史，
 * 实现多轮上下文——解决"用户确认退款时 LLM 忘了前文"的问题。
 */
@Configuration
public class ChatMemoryConfig {

    /** 窗口记忆：每会话保留最近 10 条消息（超出滚动淘汰，控 token 成本）。 */
    @Bean
    ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(10)
                .build();
    }

    /** 记忆 Advisor：自动把历史消息注入 prompt 并回写本轮对话。 */
    @Bean
    MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }
}
