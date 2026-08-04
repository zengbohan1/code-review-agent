package com.shortdrama.agent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 意图识别：LLM + 结构化输出（entity 强制 JSON），刻意不做关键词硬路由——
 * 展示模型对口语化表达的泛化能力（"我钱被扣了"也能识别为 REFUND）。
 */
@Service
public class IntentService {

    private static final String SYSTEM_PROMPT = """
            你是短剧订阅平台的意图识别模块。根据用户输入判断意图，只输出 JSON。
            意图枚举：
            - REFUND: 退款、退钱、重复扣费、扣错钱、要回钱
            - PAYMENT: 支付、订单、扣费记录、账单、发票
            - SERIES: 剧集、多少集、第几集收费、看不了
            - SUBSCRIPTION: 订阅、会员、取消订阅、自动续费
            - ESCALATE: 转人工、投诉、客服
            - CHITCHAT: 闲聊、打招呼、其他非业务内容
            slots 中提取关键实体（有则填）：orderNo 订单号、subscriptionNo 订阅编号、seriesId 剧集id、amount 金额。
            规则：
            1. confidence 为 0-1 的置信度
            2. 置信度 < 0.6，或当前意图需要订单号/订阅编号/剧集id 但用户未提供时，
               在 clarifyQuestion 字段输出一句需要向用户澄清的提问，其余字段照常输出
            3. 不要输出 JSON 以外的任何内容
            示例（少样本）：
            用户："我钱被扣了两次，能退吗" → {"intent":"REFUND","slots":{},"confidence":0.95,"clarifyQuestion":null}
            用户："订单号 ORD-20260701-0001 帮我退款" → {"intent":"REFUND","slots":{"orderNo":"ORD-20260701-0001"},"confidence":0.98,"clarifyQuestion":null}
            用户："帮我取消会员订阅" → {"intent":"SUBSCRIPTION","slots":{},"confidence":0.85,"clarifyQuestion":"请提供订阅编号，如 SUB- 开头"}
            用户："这部剧从第几集开始收费" → {"intent":"SERIES","slots":{},"confidence":0.88,"clarifyQuestion":"请问是哪部剧？"}
            用户："我要投诉，转人工" → {"intent":"ESCALATE","slots":{},"confidence":0.95,"clarifyQuestion":null}
            用户："你好" → {"intent":"CHITCHAT","slots":{},"confidence":0.9,"clarifyQuestion":null}
            """;

    private final ChatClient chatClient;

    public IntentService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 识别意图。调用失败（LLM 故障）时抛异常，由上层降级为规则兜底 + 转人工。
     */
    public IntentResult detect(String message) {
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(message)
                .call()
                .entity(IntentResult.class);
    }
}
