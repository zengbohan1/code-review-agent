package com.shortdrama.agent.agent;

import java.util.Map;

/**
 * 意图识别结构化输出（Spring AI .entity() 自动反序列化 LLM 返回的 JSON）。
 *
 * @param intent          REFUND / PAYMENT / SERIES / SUBSCRIPTION / ESCALATE / CHITCHAT
 * @param slots           抽取的实体：orderNo / subscriptionNo / seriesId / amount 等
 * @param confidence      置信度 0-1，低于阈值触发多轮澄清或转人工
 * @param clarifyQuestion 低置信度/关键槽位缺失时，LLM 生成的待澄清问题
 */
public record IntentResult(
        String intent,
        Map<String, String> slots,
        double confidence,
        String clarifyQuestion) {
}
