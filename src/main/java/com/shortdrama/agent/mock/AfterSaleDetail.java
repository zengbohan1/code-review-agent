package com.shortdrama.agent.mock;

import java.util.List;

/**
 * 售后详情（对应真实接口 GET /after-sales/{orderId}）。
 * 聚合：订单 + 退款记录 + 操作流水 + 最近失败原因。
 */
public record AfterSaleDetail(
        MockOrder order,
        List<MockRefund> refunds,
        List<MockOrderFlow> flows,
        String lastFailReason) {
}
