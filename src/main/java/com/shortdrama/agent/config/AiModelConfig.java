package com.shortdrama.agent.config;

import io.netty.channel.ChannelOption;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * DeepSeek（OpenAI 兼容层）手动装配。
 *
 * <p>Spring AI 1.1 的 OpenAI 自动配置不支持 HTTP 超时参数，这里手动构造
 * 带连接/读响应超时的 WebClient → OpenAiApi → OpenAiChatModel；
 * 自动配置因已有 OpenAiChatModel Bean 而自动退出（@ConditionalOnMissingBean）。
 * LLM 调用超时后由上层重试并降级转人工。
 */
@Configuration
public class AiModelConfig {

    @Value("${DEEPSEEK_BASE_URL:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${DEEPSEEK_API_KEY:}")
    private String apiKey;

    @Value("${DEEPSEEK_MODEL:deepseek-chat}")
    private String model;

    @Value("${llm.temperature:0.7}")
    private double temperature;

    @Value("${llm.max-tokens:1024}")
    private int maxTokens;

    @Value("${llm.connect-timeout-ms:10000}")
    private long connectTimeoutMs;

    @Value("${llm.read-timeout-s:60}")
    private long readTimeoutSec;

    @Bean
    OpenAiChatModel openAiChatModel() {
        // 带超时的 WebClient：连接超时 + 响应超时，超时抛错由重试策略接管
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeoutMs)
                .responseTimeout(Duration.ofSeconds(readTimeoutSec));
        WebClient.Builder webClientBuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("llm.api-key 未配置（环境变量 DEEPSEEK_API_KEY）");
        }
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .webClientBuilder(webClientBuilder)
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }
}
