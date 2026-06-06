package com.shortdrama.agent.cost;

import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Token 成本采集 Advisor（横切，业务代码零侵入）。
 *
 * <p>非流式调用在 adviseCall 返回后取 usage 记账；流式调用用
 * ChatClientMessageAggregator 把整个流聚合成完整响应后记账
 * （流式响应的 token 统计只在结束时才有完整值）。
 */
@Component
public class CostAdvisor implements CallAdvisor, StreamAdvisor {

    private final TokenCostService costService;
    private final ChatClientMessageAggregator aggregator = new ChatClientMessageAggregator();

    public CostAdvisor(TokenCostService costService) {
        this.costService = costService;
    }

    @Override
    public String getName() {
        return "cost-advisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        costService.record(response);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return aggregator.aggregateChatClientResponse(chain.nextStream(request), costService::record);
    }
}
