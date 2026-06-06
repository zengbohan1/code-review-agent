package com.shortdrama.agent.cost;

import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 成本统计：Advisor 每次调用后累计 usage，按 DeepSeek 定价记账。
 *
 * <p>统计粒度：进程内累计（演示口径足够）；生产环境可换 Redis/时序库。
 * 通过 GET /api/cost 查询。
 */
@Service
public class TokenCostService {

    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong callCount = new AtomicLong();

    /** 记录一次 LLM 调用的 token 用量（从 ChatClientResponse 的 usage 提取）。 */
    public void record(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getMetadata() == null
                || response.chatResponse().getMetadata().getUsage() == null) {
            return;
        }
        var usage = response.chatResponse().getMetadata().getUsage();
        if (usage.getPromptTokens() != null) {
            promptTokens.addAndGet(usage.getPromptTokens());
        }
        if (usage.getCompletionTokens() != null) {
            completionTokens.addAndGet(usage.getCompletionTokens());
        }
        callCount.incrementAndGet();
    }

    /** 成本快照（供 CostController 返回）。 */
    public CostSnapshot snapshot() {
        long p = promptTokens.get();
        long c = completionTokens.get();
        return new CostSnapshot(
                callCount.get(),
                p,
                c,
                p + c,
                DeepSeekPricing.costOf(p, c));
    }

    /** 成本快照结构。 */
    public record CostSnapshot(long calls, long promptTokens, long completionTokens,
                               long totalTokens, BigDecimal costUsd) {
    }
}
