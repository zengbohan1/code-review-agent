package com.shortdrama.agent.mock;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * mock 订单（对应真实接口 GET /orders/search 返回的订单模型）。
 *
 * @param payPlatform 支付平台 1-5：Google / Apple / Antom / Stripe / PayPal
 * @param status      PAID / REFUNDING / REFUNDED / CANCELLED
 */
public record MockOrder(
        Long id,
        String orderNo,
        String paymentId,
        int payPlatform,
        BigDecimal amount,
        String currency,
        String status,
        LocalDateTime createdAt) {
}
