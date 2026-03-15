package com.shortdrama.agent.mock;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * mock 退款记录。双确认（confirmed + secondaryConfirmed）是服务端强校验，
 * 防止绕过页面直接调用高风险退款接口。
 */
public record MockRefund(
        Long id,
        Long orderId,
        String refundNo,
        BigDecimal amount,
        String status,               // PENDING / SUCCESS / FAILED
        boolean confirmed,
        boolean secondaryConfirmed,
        boolean allowUsedEquity,
        String reason,
        LocalDateTime createdAt,
        LocalDateTime successAt) {
}
