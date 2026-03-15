package com.shortdrama.agent.mock;

import java.time.LocalDateTime;

/**
 * mock 订单流水：状态流转留痕（SEARCH/PREVIEW/REFUND/CANCEL/PAYMENT）。
 */
public record MockOrderFlow(
        Long id,
        Long orderId,
        String flowType,
        String fromStatus,
        String toStatus,
        String operator,             // AGENT=智能客服 / USER
        String remark,
        LocalDateTime createdAt) {
}
