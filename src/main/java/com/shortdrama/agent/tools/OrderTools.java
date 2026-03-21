package com.shortdrama.agent.tools;

import com.shortdrama.agent.mock.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 客服 Agent 的函数调用工具集 —— 6 个 @Tool，语义对齐真实客诉接口：
 * <pre>
 *   search_order        <- GET  /orders/search
 *   get_order_preview   <- GET  /orders/{id}/preview
 *   refund              <- POST /refund
 *   cancel_subscription <- POST /cancel-subscription
 *   get_after_sale_detail<- GET /after-sales/{orderId}
 *   get_series_info     <- content 域 Series 语义
 * </pre>
 * 每个工具的描述是给 LLM 的"何时调用"说明——函数调用质量取决于描述质量。
 */
@Component
public class OrderTools {

    private final MockOrderService orders;

    public OrderTools(MockOrderService orders) {
        this.orders = orders;
    }

    /**
     * 按订单号搜索订单。多命中时返回全部候选，由 Agent 向用户确认具体订单，
     * 不要自动挑选（防止多订单用户操作错单）。
     */
    @Tool(name = "search_order",
            description = "按订单号搜索用户的订单。返回可能多个候选订单，须向用户确认具体是哪一单后再继续。"
                    + " payPlatform 可选：1=Google 2=Apple 3=Antom 4=Stripe 5=PayPal，用户提到支付渠道时传入。")
    public List<MockOrder> searchOrder(
            @ToolParam(description = "订单号，用户提供的完整或部分订单号") String orderNo,
            @ToolParam(description = "支付平台代码 1-5，可选", required = false) Integer payPlatform) {
        return orders.searchOrders(orderNo, payPlatform);
    }

    /**
     * 退款预检：剩余可退金额、权益快照、历史退款。退款操作前必须先调用本工具，
     * 与用户确认可退金额后再执行退款。
     */
    @Tool(name = "get_order_preview",
            description = "退款前的预检接口：返回订单支付金额、已退金额、剩余可退金额与权益快照。"
                    + " 执行退款前必须先调用本工具并向用户确认金额。")
    public PreviewResult getOrderPreview(
            @ToolParam(description = "订单数据库 id（来自 search_order 返回结果的 id 字段）") long orderId) {
        return orders.previewRefund(orderId);
    }

    /**
     * 执行退款。服务端强校验双重确认（confirmed + secondaryConfirmed），
     * 两个确认都必须为 true 才会受理；分笔退款累计不超支付金额。
     */
    @Tool(name = "refund",
            description = "执行退款。必须同时满足：①用户已二次确认退款（confirmed 与 secondaryConfirmed 都传 true）；"
                    + " ②已通过 get_order_preview 预检。分笔退款累计不得超过支付金额。"
                    + " 全额退款且权益可能已使用时 allowUsedEquity 传 true。退款原因 reason 必填。")
    public String refund(
            @ToolParam(description = "订单数据库 id") long orderId,
            @ToolParam(description = "本次退款金额，必须不超过预检的剩余可退金额") BigDecimal amount,
            @ToolParam(description = "用户第一次确认退款") boolean confirmed,
            @ToolParam(description = "用户第二次确认退款（防误操作，必须为 true）") boolean secondaryConfirmed,
            @ToolParam(description = "全额退款且涉及已使用权益时传 true，否则 false", required = false) boolean allowUsedEquity,
            @ToolParam(description = "退款原因，如'重复扣费'") String reason) {
        return orders.refund(orderId, amount, confirmed, secondaryConfirmed, allowUsedEquity, reason);
    }

    /**
     * 取消订阅：先落库再请求平台，状态由平台回调决定（mock 模拟为立即成功）。
     */
    @Tool(name = "cancel_subscription",
            description = "取消用户的订阅（如月度/年度会员）。取消后不再扣费。"
                    + " 调用前须与用户确认订阅编号；订阅状态非 ACTIVE 时不可取消。")
    public String cancelSubscription(
            @ToolParam(description = "订阅编号，如 SUB-20260601-A1") String subscriptionNo) {
        return orders.cancelSubscription(subscriptionNo);
    }

    /**
     * 售后详情：订单 + 退款记录 + 操作流水 + 最近失败原因。
     * 用户追问"退款到哪了""为什么失败"时调用。
     */
    @Tool(name = "get_after_sale_detail",
            description = "查询售后详情：订单状态、退款记录、操作流水、最近一次失败原因。"
                    + " 用户询问退款进度、退款失败原因、订单处理记录时调用。")
    public AfterSaleDetail getAfterSaleDetail(
            @ToolParam(description = "订单数据库 id") long orderId) {
        return orders.afterSaleDetail(orderId);
    }

    /**
     * 剧集信息：总集数、免费集数（付费卡点）、单集价格。用户问"多少集""第几集收费"时调用。
     */
    @Tool(name = "get_series_info",
            description = "查询短剧剧集信息：总集数、免费集数（付费卡点）、单集价格。"
                    + " 用户询问某部剧多少集、从第几集开始收费、单集价格时调用。")
    public SeriesInfo getSeriesInfo(
            @ToolParam(description = "剧集 id，如 S1001（用户常只报剧名，可先模糊匹配再让用户确认）") String seriesId) {
        return orders.seriesInfo(seriesId);
    }
}
