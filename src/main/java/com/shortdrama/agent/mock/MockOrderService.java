package com.shortdrama.agent.mock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * mock 订单/订阅/退款系统的服务层。
 *
 * <p>基于真实客诉接口文档（order_customer_service_api.md 等）的业务语义实现：
 * <ul>
 *   <li>订单搜索：多命中不自动选（防止客服误操作错单）</li>
 *   <li>退款：双确认（confirmed + secondaryConfirmed）强校验，分笔累计不超支付额</li>
 *   <li>取消订阅：先落库 CANCELLING，再由"平台回调"（mock 模拟）驱动最终状态</li>
 *   <li>所有操作写流水（mock_order_flow），售后详情可回溯</li>
 * </ul>
 */
@Service
public class MockOrderService {

    private static final String PAY_PLATFORM_NAMES = "Google/Apple/Antom/Stripe/PayPal";

    private final JdbcTemplate jdbc;

    public MockOrderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<MockOrder> ORDER_MAPPER = (rs, i) -> new MockOrder(
            rs.getLong("id"),
            rs.getString("order_no"),
            rs.getString("payment_id"),
            rs.getInt("pay_platform"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<MockRefund> REFUND_MAPPER = (rs, i) -> new MockRefund(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getString("refund_no"),
            rs.getBigDecimal("amount"),
            rs.getString("status"),
            rs.getBoolean("confirmed"),
            rs.getBoolean("secondary_confirmed"),
            rs.getBoolean("allow_used_equity"),
            rs.getString("reason"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            Optional.ofNullable(rs.getTimestamp("success_at")).map(ts -> ts.toLocalDateTime()).orElse(null));

    private static final RowMapper<MockSubscription> SUB_MAPPER = (rs, i) -> new MockSubscription(
            rs.getLong("id"),
            rs.getString("subscription_no"),
            rs.getString("user_id"),
            rs.getString("plan_name"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getString("status"),
            Optional.ofNullable(rs.getTimestamp("next_billing_at")).map(ts -> ts.toLocalDateTime()).orElse(null),
            Optional.ofNullable(rs.getTimestamp("cancelled_at")).map(ts -> ts.toLocalDateTime()).orElse(null));

    private static final RowMapper<MockOrderFlow> FLOW_MAPPER = (rs, i) -> new MockOrderFlow(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getString("flow_type"),
            rs.getString("from_status"),
            rs.getString("to_status"),
            rs.getString("operator"),
            rs.getString("remark"),
            rs.getTimestamp("created_at").toLocalDateTime());

    /** 支付平台名称（1-5），用于话术展示。 */
    public static String payPlatformName(int code) {
        return PAY_PLATFORM_NAMES.split("/")[code - 1];
    }

    // ---------------- 订单搜索 ----------------

    /**
     * 订单搜索：按订单号（支持平台过滤），多命中返回全部——不自动选单，
     * 由 Agent 向用户确认具体订单（真实语义：避免多订单用户错单操作）。
     */
    public List<MockOrder> searchOrders(String orderNo, Integer payPlatform) {
        StringBuilder sql = new StringBuilder("SELECT * FROM mock_order WHERE order_no ILIKE ?");
        Object[] args = { "%" + orderNo.trim() + "%" };
        if (payPlatform != null) {
            sql.append(" AND pay_platform = ?");
            args = new Object[]{ "%" + orderNo.trim() + "%", payPlatform };
        }
        return jdbc.query(sql.toString(), ORDER_MAPPER, args);
    }

    public Optional<MockOrder> getOrder(long orderId) {
        return jdbc.query("SELECT * FROM mock_order WHERE id = ?", ORDER_MAPPER, orderId)
                .stream().findFirst();
    }

    public Optional<MockSubscription> getSubscription(String subscriptionNo) {
        return jdbc.query("SELECT * FROM mock_subscription WHERE subscription_no = ?", SUB_MAPPER, subscriptionNo)
                .stream().findFirst();
    }

    // ---------------- 退款预检 ----------------

    /**
     * 退款预检：剩余可退金额 = 支付金额 - 已成功退款累计；
     * 返回权益快照与历史退款，供 Agent 向用户确认后再执行退款。
     */
    public PreviewResult previewRefund(long orderId) {
        MockOrder order = getOrder(orderId)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderId));
        BigDecimal refunded = refundedAmount(orderId);
        return new PreviewResult(
                order.id(), order.orderNo(), order.amount(), refunded,
                order.amount().subtract(refunded), order.status(),
                equitySnapshot(order), refunds(orderId));
    }

    private BigDecimal refundedAmount(long orderId) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM mock_refund WHERE order_id = ? AND status = 'SUCCESS'",
                BigDecimal.class, orderId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private List<MockRefund> refunds(long orderId) {
        return jdbc.query("SELECT * FROM mock_refund WHERE order_id = ? ORDER BY created_at DESC",
                REFUND_MAPPER, orderId);
    }

    /** 权益快照：简单语义——已退款订单视为权益已释放，其余为"权益未消耗"。 */
    private String equitySnapshot(MockOrder order) {
        return "REFUNDED".equals(order.status()) ? "权益已释放" : "权益未消耗";
    }

    // ---------------- 退款（双确认 + 防超额） ----------------

    /**
     * 执行退款。
     *
     * <p>业务规则（面试深挖点）：
     * <ol>
     *   <li><b>双确认</b>：confirmed + secondaryConfirmed 必须同时为 true，
     *       否则直接拒绝——真实接口文档写明"服务端校验确认参数，避免绕过页面直接调用高风险操作"</li>
     *   <li><b>分笔防超额</b>：同订单已成功退款累计 + 本次 ≤ 支付金额；
     *       事务内 SELECT ... FOR UPDATE 锁行，杜绝并发下超额</li>
     *   <li><b>状态条件更新</b>：仅 PAID 订单可发起退款（REFUNDED/CANCELLED 拒绝）</li>
     *   <li>已用权益需 allowUsedEquity=true 才允许退款</li>
     * </ol>
     *
     * @return 退款结果描述；失败时抛 IllegalArgumentException（由全局异常转成用户话术）
     */
    @Transactional
    public String refund(long orderId, BigDecimal amount, boolean confirmed,
                         boolean secondaryConfirmed, boolean allowUsedEquity, String reason) {
        // 规则 1：双确认强校验
        if (!confirmed || !secondaryConfirmed) {
            throw new IllegalArgumentException("退款需双重确认（confirmed + secondaryConfirmed），请与用户二次确认后重试");
        }
        // 行级锁：防并发下分笔退款超额
        MockOrder order = jdbc.query(
                        "SELECT * FROM mock_order WHERE id = ? FOR UPDATE", ORDER_MAPPER, orderId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderId));
        if (!"PAID".equals(order.status())) {
            throw new IllegalArgumentException("订单当前状态为 " + order.status() + "，不可退款");
        }
        // 规则 3：累计校验
        BigDecimal refunded = refundedAmount(orderId);
        if (refunded.add(amount).compareTo(order.amount()) > 0) {
            throw new IllegalArgumentException(
                    "退款金额超限：本单已退 " + refunded + "，剩余可退 " + order.amount().subtract(refunded));
        }
        // 规则 4：已用权益
        if (amount.compareTo(order.amount()) == 0 && !allowUsedEquity) {
            throw new IllegalArgumentException("全额退款涉及已使用权益，需 allowUsedEquity=true 确认");
        }
        // 落退款单（PENDING）→ mock 平台回调置 SUCCESS
        String refundNo = "RF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("""
                INSERT INTO mock_refund (order_id, refund_no, amount, status, confirmed,
                                         secondary_confirmed, allow_used_equity, reason, success_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, now())
                """, orderId, refundNo, amount, confirmed, secondaryConfirmed, allowUsedEquity, reason);
        jdbc.update("UPDATE mock_order SET status = 'REFUNDING' WHERE id = ?", orderId);
        writeFlow(orderId, "REFUND", "PAID", "REFUNDING", "退款发起，等待平台回调");
        // mock 平台回调：立即成功（真实系统由支付平台异步回调驱动）
        jdbc.update("UPDATE mock_refund SET status = 'SUCCESS', success_at = now() WHERE refund_no = ?", refundNo);
        jdbc.update("UPDATE mock_order SET status = 'REFUNDED' WHERE id = ? AND status = 'REFUNDING'", orderId);
        writeFlow(orderId, "REFUND", "REFUNDING", "REFUNDED", "平台回调确认退款成功");
        return "退款成功，退款单号 " + refundNo + "，金额 " + amount + " " + order.currency();
    }

    // ---------------- 取消订阅 ----------------

    /**
     * 取消订阅：<b>先落库再请求平台</b>。
     * 状态流转 ACTIVE -> CANCELLING -> CANCELLED，CANCELLED 由平台回调（mock 模拟）决定，
     * 与真实接口语义一致；回调失败时订单停留在 CANCELLING 可重试。
     */
    @Transactional
    public String cancelSubscription(String subscriptionNo) {
        MockSubscription sub = getSubscription(subscriptionNo)
                .orElseThrow(() -> new IllegalArgumentException("订阅不存在: " + subscriptionNo));
        if (!"ACTIVE".equals(sub.status())) {
            throw new IllegalArgumentException("订阅当前状态为 " + sub.status() + "，无需取消");
        }
        // 先落库 CANCELLING（真实语义：取消请求先持久化，避免平台超时丢单）
        int updated = jdbc.update(
                "UPDATE mock_subscription SET status = 'CANCELLING' WHERE id = ? AND status = 'ACTIVE'", sub.id());
        if (updated == 0) {
            throw new IllegalArgumentException("订阅状态已变化，请刷新后重试");
        }
        // mock 平台回调：取消成功（真实系统由 Stripe/Google 等平台回调驱动）
        jdbc.update("UPDATE mock_subscription SET status = 'CANCELLED', cancelled_at = now() WHERE id = ? AND status = 'CANCELLING'",
                sub.id());
        return "订阅 " + subscriptionNo + " 已取消，后续不会再扣费";
    }

    // ---------------- 售后详情 ----------------

    /**
     * 售后详情：订单 + 退款记录 + 操作流水 + 最近一次失败原因（真实语义：
     * 客诉跟进时先看失败原因，避免重复失败操作）。
     */
    public AfterSaleDetail afterSaleDetail(long orderId) {
        MockOrder order = getOrder(orderId)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderId));
        List<MockRefund> refunds = refunds(orderId);
        List<MockOrderFlow> flows = jdbc.query(
                "SELECT * FROM mock_order_flow WHERE order_id = ? ORDER BY created_at DESC", FLOW_MAPPER, orderId);
        String lastFail = flows.stream()
                .filter(f -> f.remark() != null && f.remark().contains("失败"))
                .findFirst().map(MockOrderFlow::remark).orElse(null);
        return new AfterSaleDetail(order, refunds, flows, lastFail);
    }

    // ---------------- 剧集信息（content 域） ----------------

    /** 剧集信息：mock 数据，覆盖"剧集问题"意图（多少集、付费卡点、单集价格）。 */
    public SeriesInfo seriesInfo(String seriesId) {
        return switch (seriesId.toUpperCase()) {
            case "S1001" -> new SeriesInfo("S1001", "重生之都市修仙", 80, 10, new BigDecimal("0.99"), "USD");
            case "S1002" -> new SeriesInfo("S1002", "隐婚老公是大佬", 60, 8, new BigDecimal("0.89"), "USD");
            case "S1003" -> new SeriesInfo("S1003", "千金归来", 100, 12, new BigDecimal("0.99"), "USD");
            default -> throw new IllegalArgumentException("剧集不存在: " + seriesId);
        };
    }

    // ---------------- 流水 ----------------

    private void writeFlow(long orderId, String flowType, String fromStatus, String toStatus, String remark) {
        jdbc.update("""
                INSERT INTO mock_order_flow (order_id, flow_type, from_status, to_status, operator, remark)
                VALUES (?, ?, ?, ?, 'AGENT', ?)
                """, orderId, flowType, fromStatus, toStatus, remark);
    }

    /** 工单落库（转人工兜底产物）。 */
    public String createTicket(String sessionId, String userText, String reason) {
        String ticketNo = "TICKET-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("""
                INSERT INTO support_ticket (ticket_no, session_id, user_text, reason)
                VALUES (?, ?, ?, ?)
                """, ticketNo, sessionId, userText, reason);
        return ticketNo;
    }
}
