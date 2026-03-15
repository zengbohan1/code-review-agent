package com.shortdrama.agent.mock;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * mock 订阅（对应真实接口 POST /cancel-subscription 涉及的对象）。
 *
 * @param status ACTIVE / CANCELLING / CANCELLED —— 取消后先落库 CANCELLING，
 *               最终状态由"平台回调"（mock）决定
 */
public record MockSubscription(
        Long id,
        String subscriptionNo,
        String userId,
        String planName,
        BigDecimal amount,
        String currency,
        String status,
        LocalDateTime nextBillingAt,
        LocalDateTime cancelledAt) {
}
