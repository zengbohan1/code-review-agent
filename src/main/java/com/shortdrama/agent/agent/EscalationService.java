package com.shortdrama.agent.agent;

import com.shortdrama.agent.mock.MockOrderService;
import org.springframework.stereotype.Service;

/**
 * 转人工兜底：置信度过低 / LLM 故障 / 用户明确要求人工时，
 * 生成工单（support_ticket 落库）并返回转人工话术。
 */
@Service
public class EscalationService {

    private final MockOrderService orders;

    public EscalationService(MockOrderService orders) {
        this.orders = orders;
    }

    /** 生成工单并返回转人工话术（含工单号，用户可凭号跟进）。 */
    public String escalate(String sessionId, String userText, String reason) {
        String ticketNo = orders.createTicket(sessionId, userText, reason);
        return "已为您转接人工客服，工单号 " + ticketNo
                + "。请保持在线，人工客服将尽快联系您，感谢理解！";
    }
}
