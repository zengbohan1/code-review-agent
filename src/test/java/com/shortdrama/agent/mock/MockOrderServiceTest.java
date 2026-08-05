package com.shortdrama.agent.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mock 订单服务业务规则测试（本地 PostgreSQL，事务回滚不污染数据）。
 *
 * <p>覆盖：退款双确认、分笔防超额、部分/全额退款状态流转、取消订阅状态机、订单搜索多命中。
 */
@SpringBootTest
@Transactional
class MockOrderServiceTest {

    @Autowired
    private MockOrderService orders;

    @Autowired
    private JdbcTemplate jdbc;

    /** 种子订单 ORD-20260701-0001：id=1，金额 9.99，PAID。 */
    private static final long ORDER_ID = 1L;

    /** 每用例前重置数据状态（种子数据可能被历史运行修改），保证测试独立。 */
    @BeforeEach
    void resetData() {
        jdbc.execute("DELETE FROM mock_refund");
        jdbc.execute("DELETE FROM mock_order_flow");
        jdbc.update("UPDATE mock_order SET status = 'PAID'");
        jdbc.update("UPDATE mock_subscription SET status = 'ACTIVE', cancelled_at = NULL");
    }

    // ---------------- 退款双确认 ----------------

    @Test
    void refund_withoutDoubleConfirmation_isRejected() {
        assertThatThrownBy(() -> orders.refund(ORDER_ID, new BigDecimal("5.00"), true, false, false, "测试"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("双重确认");
    }

    @Test
    void refund_withBothConfirmations_succeeds() {
        String result = orders.refund(ORDER_ID, new BigDecimal("5.00"), true, true, false, "重复扣费");
        assertThat(result).contains("退款成功");
    }

    // ---------------- 分笔防超额 ----------------

    @Test
    void refund_overPaymentAmount_isRejected() {
        assertThatThrownBy(() -> orders.refund(ORDER_ID, new BigDecimal("99.99"), true, true, false, "超额"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超限");
    }

    @Test
    void partialRefund_keepsOrderPaid_thenAccumulatedOverLimit_isRejected() {
        // 第一笔部分退款：订单保持 PAID
        orders.refund(ORDER_ID, new BigDecimal("5.00"), true, true, false, "第一笔");
        MockOrder afterFirst = orders.getOrder(ORDER_ID).orElseThrow();
        assertThat(afterFirst.status()).isEqualTo("PAID");
        // 第二笔累计超限：5.00 + 9.00 > 9.99 → 拒绝
        assertThatThrownBy(() -> orders.refund(ORDER_ID, new BigDecimal("9.00"), true, true, false, "超额"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超限");
    }

    @Test
    void partialRefunds_accumulatedToFull_amountMarksOrderRefunded() {
        orders.refund(ORDER_ID, new BigDecimal("5.00"), true, true, false, "第一笔");
        orders.refund(ORDER_ID, new BigDecimal("4.99"), true, true, true, "第二笔（累计达全额）");
        MockOrder order = orders.getOrder(ORDER_ID).orElseThrow();
        assertThat(order.status()).isEqualTo("REFUNDED");
    }

    @Test
    void accumulatedToFull_withoutAllowUsedEquity_isRejected() {
        orders.refund(ORDER_ID, new BigDecimal("5.00"), true, true, false, "第一笔");
        assertThatThrownBy(() -> orders.refund(ORDER_ID, new BigDecimal("4.99"), true, true, false, "累计全额"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowUsedEquity");
    }

    @Test
    void refund_onRefundedOrder_isRejected() {
        orders.refund(ORDER_ID, new BigDecimal("9.99"), true, true, true, "全额");
        assertThatThrownBy(() -> orders.refund(ORDER_ID, new BigDecimal("1.00"), true, true, false, "重复退"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不可退款");
    }

    // ---------------- 全额退款与权益 ----------------

    @Test
    void fullRefund_withoutAllowUsedEquity_isRejected() {
        assertThatThrownBy(() -> orders.refund(ORDER_ID, new BigDecimal("9.99"), true, true, false, "全额"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowUsedEquity");
    }

    // ---------------- 取消订阅状态机 ----------------

    @Test
    void cancelSubscription_activeToCancelled() {
        String result = orders.cancelSubscription("SUB-20260601-A1");
        assertThat(result).contains("已取消");
        MockSubscription sub = orders.getSubscription("SUB-20260601-A1").orElseThrow();
        assertThat(sub.status()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelSubscription_alreadyCancelled_isRejected() {
        orders.cancelSubscription("SUB-20260601-A1");
        assertThatThrownBy(() -> orders.cancelSubscription("SUB-20260601-A1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无需取消");
    }

    // ---------------- 订单搜索 ----------------

    @Test
    void searchOrder_byPartialOrderNo_returnsMatches() {
        List<MockOrder> ordersFound = orders.searchOrders("ORD-20260701", null);
        assertThat(ordersFound).isNotEmpty();
        assertThat(ordersFound).allMatch(o -> o.orderNo().contains("ORD-20260701"));
    }

    @Test
    void searchOrder_withPlatformFilter_filtersResults() {
        List<MockOrder> ordersFound = orders.searchOrders("ORD", 1);   // Google
        assertThat(ordersFound).isNotEmpty();
        assertThat(ordersFound).allMatch(o -> o.payPlatform() == 1);
    }

    @Test
    void searchOrder_noMatch_returnsEmpty() {
        assertThat(orders.searchOrders("NOT-EXISTS", null)).isEmpty();
    }

    // ---------------- 退款预检 ----------------

    @Test
    void previewRefund_showsRemainingAmount() {
        orders.refund(ORDER_ID, new BigDecimal("5.00"), true, true, false, "第一笔");
        PreviewResult preview = orders.previewRefund(ORDER_ID);
        assertThat(preview.refundedAmount()).isEqualByComparingTo("5.00");
        assertThat(preview.refundableAmount()).isEqualByComparingTo("4.99");
    }
}
