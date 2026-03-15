package com.shortdrama.agent.mock;

import java.math.BigDecimal;
import java.util.List;

/**
 * 退款预检结果（对应真实接口 GET /orders/{id}/preview）。
 * 操作前预检：剩余可退金额、权益快照、历史退款，防止超额与误操作。
 */
public record PreviewResult(
        Long orderId,
        String orderNo,
        BigDecimal paidAmount,          // 支付金额
        BigDecimal refundedAmount,      // 已成功退款累计
        BigDecimal refundableAmount,    // 剩余可退金额 = paid - refunded
        String status,
        String equitySnapshot,          // 权益快照（已用/未用权益描述）
        List<MockRefund> refunds) {
}
