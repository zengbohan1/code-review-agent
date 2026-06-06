package com.shortdrama.agent.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * DeepSeek 官方定价表（美元 / 百万 token，按 deepseek-chat 口径）。
 *
 * <p>口径说明（README 同步）：输入未命中缓存 0.27$/M，命中缓存 0.07$/M，
 * 输出 1.10$/M。成本统计为展示"生产化可观测"能力的近似值，
 * 精确对账以 DeepSeek 后台账单为准。
 */
public final class DeepSeekPricing {

    /** 输入 token 单价（未命中缓存）。 */
    public static final BigDecimal INPUT_PER_1M = new BigDecimal("0.27");

    /** 输出 token 单价。 */
    public static final BigDecimal OUTPUT_PER_1M = new BigDecimal("1.10");

    private DeepSeekPricing() {
    }

    /** 按 token 数计算美元成本（保留 6 位小数，方便演示累计）。 */
    public static BigDecimal costOf(long promptTokens, long completionTokens) {
        BigDecimal promptCost = BigDecimal.valueOf(promptTokens)
                .multiply(INPUT_PER_1M).divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        BigDecimal completionCost = BigDecimal.valueOf(completionTokens)
                .multiply(OUTPUT_PER_1M).divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return promptCost.add(completionCost);
    }
}
