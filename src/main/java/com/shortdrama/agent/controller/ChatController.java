package com.shortdrama.agent.controller;

import com.shortdrama.agent.agent.ClarifyService;
import com.shortdrama.agent.agent.EscalationService;
import com.shortdrama.agent.agent.IntentResult;
import com.shortdrama.agent.agent.IntentService;
import com.shortdrama.agent.cost.CostAdvisor;
import com.shortdrama.agent.tools.OrderTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.http.MediaType;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 客服 Agent 主入口：意图识别 → 多轮澄清 / 工具调用 / 转人工兜底。
 *
 * <p>两个端点：
 * <ul>
 *   <li>POST /api/chat —— 非流式（兜底 / 联调）</li>
 *   <li>POST /api/chat/stream —— SSE 流式（首事件推送意图，随后逐 token 推送回答）</li>
 * </ul>
 * 流式与非流式共用一次意图识别（避免重复调用 LLM 导致行为不一致）；
 * LLM 调用带重试，失败/超时统一降级为规则兜底 + 转人工，系统不瘫。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** 置信度阈值：低于则先澄清，澄清超限转人工。 */
    private static final double CONFIDENCE_THRESHOLD = 0.6;

    /** 流式推送线程池：固定 8 线程，超出排队（避免无界线程拖垮系统）。 */
    private final ExecutorService streamPool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "sse-pusher");
        t.setDaemon(true);
        return t;
    });

    /** 客服角色设定：约束工具调用路径（先搜索→预检→确认→退款）。 */
    private static final String CHAT_SYSTEM = """
            你是"短剧"短剧订阅平台的智能客服助手，语气专业、简洁、有耐心。
            工具使用规范：
            1. 退款：search_order 定位订单 → get_order_preview 预检可退金额 → 与用户确认金额后 refund。
               退款必须经用户明确同意，不允许替用户做主；退款前向用户说明退款金额。
            2. 取消订阅：先与用户确认订阅编号，再 cancel_subscription。
            3. 订单/支付问题：用 search_order、get_after_sale_detail 查询后如实回答。
            4. 剧集问题：用户提到剧名时先用 get_series_info 查询（剧集id：重生之都市修仙=S1001、
               隐婚老公是大佬=S1002、千金归来=S1003），如剧名不符请询问确认。
            5. 缺少订单号/订阅编号等关键信息时，先向用户询问，不要臆造。
            6. 工具返回错误时，如实转达原因，并给出下一步建议。
            """;

    private final ChatClient chatClient;
    private final IntentService intentService;
    private final EscalationService escalationService;
    private final ClarifyService clarifyService;
    private final OrderTools orderTools;
    private final MessageChatMemoryAdvisor memoryAdvisor;
    private final QuestionAnswerAdvisor qaAdvisor;
    private final CostAdvisor costAdvisor;
    private final RetryTemplate retryTemplate;

    public ChatController(ChatClient chatClient, IntentService intentService,
                          EscalationService escalationService, ClarifyService clarifyService,
                          OrderTools orderTools, MessageChatMemoryAdvisor memoryAdvisor,
                          QuestionAnswerAdvisor qaAdvisor, CostAdvisor costAdvisor,
                          RetryTemplate retryTemplate) {
        this.chatClient = chatClient;
        this.intentService = intentService;
        this.escalationService = escalationService;
        this.clarifyService = clarifyService;
        this.orderTools = orderTools;
        this.memoryAdvisor = memoryAdvisor;
        this.qaAdvisor = qaAdvisor;
        this.costAdvisor = costAdvisor;
        this.retryTemplate = retryTemplate;
    }

    /** 入参：message 用户消息；sessionId 会话标识（用于澄清轮次与记忆），缺省为 default。 */
    public record ChatRequest(String message, String sessionId) {
    }

    /** 出参：reply 回复；intent 识别出的意图；sessionId 回显。 */
    public record ChatResponse(String reply, String intent, String sessionId) {
    }

    // ---------------- 非流式 ----------------

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String sessionId = normalizeSession(request.sessionId());
        String message = request.message();
        IntentResult intent = detectIntent(sessionId, message);
        String reply = intent == null
                ? escalationService.escalate(sessionId, message, "LLM_FAILURE")
                : handle(sessionId, intent, message);
        return new ChatResponse(reply, intent == null ? "ESCALATE" : intent.intent(), sessionId);
    }

    /**
     * 意图识别（带重试）。识别失败返回 null，由调用方降级转人工。
     */
    private IntentResult detectIntent(String sessionId, String message) {
        try {
            IntentResult intent = retryTemplate.execute(ctx -> intentService.detect(message));
            log.debug("intent={} confidence={} slots={}", intent.intent(), intent.confidence(), intent.slots());
            return intent;
        } catch (Exception e) {
            log.warn("intent detect failed after retries, sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 意图路由后的回答（非流式）：低置信度澄清，超限转人工。
     */
    private String handle(String sessionId, IntentResult intent, String message) {
        if (intent.confidence() < CONFIDENCE_THRESHOLD) {
            return clarifyReply(sessionId, intent, message);
        }
        clarifyService.clear(sessionId);
        return route(sessionId, intent.intent(), message);
    }

    /**
     * 按意图路由：转人工 / 闲聊（无工具）/ 业务（工具 + RAG），均带记忆与成本采集。
     */
    private String route(String sessionId, String intent, String message) {
        return switch (intent) {
            case "ESCALATE" -> escalationService.escalate(sessionId, message, "USER_REQUEST");
            case "CHITCHAT" -> chatClient.prompt()
                    .system(CHAT_SYSTEM)
                    // 1.1.x 的会话键为 chat_memory_conversation_id（非 conversationId）
                    .advisors(a -> a.param("chat_memory_conversation_id", sessionId)
                            .advisors(memoryAdvisor, costAdvisor))
                    .user(message).call().content();
            default -> chatClient.prompt()
                    .system(CHAT_SYSTEM)
                    .advisors(a -> a.param("chat_memory_conversation_id", sessionId)
                            .advisors(memoryAdvisor, qaAdvisor, costAdvisor))
                    .tools(orderTools)
                    .user(message)
                    .call().content();
        };
    }

    /** 构建带会话参数的 prompt spec（流式端点用，与 route 同一套 advisors/工具）。 */
    private ChatClient.ChatClientRequestSpec buildSpec(String sessionId, String intent) {
        return "CHITCHAT".equals(intent)
                ? chatClient.prompt().system(CHAT_SYSTEM)
                        .advisors(a -> a.param("chat_memory_conversation_id", sessionId)
                                .advisors(memoryAdvisor, costAdvisor))
                : chatClient.prompt().system(CHAT_SYSTEM)
                        .advisors(a -> a.param("chat_memory_conversation_id", sessionId)
                                .advisors(memoryAdvisor, qaAdvisor, costAdvisor))
                        .tools(orderTools);
    }

    // ---------------- SSE 流式 ----------------

    /**
     * SSE 流式端点：意图识别走非流式（需结构化结果），回答部分逐 token 推送。
     * 首事件 event:intent 携带识别出的意图；生成阶段失败降级为转人工话术，连接不中断。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        String sessionId = normalizeSession(request.sessionId());
        SseEmitter emitter = new SseEmitter(120_000L);

        streamPool.execute(() -> {
            IntentResult intent = detectIntent(sessionId, request.message());
            if (intent == null) {
                sendAndComplete(emitter, escalationService.escalate(sessionId, request.message(), "LLM_FAILURE"));
                return;
            }
            // 澄清/转人工无流式意义，整段推送
            if (intent.confidence() < CONFIDENCE_THRESHOLD || "ESCALATE".equals(intent.intent())) {
                String reply = intent.confidence() < CONFIDENCE_THRESHOLD
                        ? clarifyReply(sessionId, intent, request.message())
                        : escalationService.escalate(sessionId, request.message(), "USER_REQUEST");
                sendAndComplete(emitter, reply);
                return;
            }
            clarifyService.clear(sessionId);

            try {
                emitter.send(SseEmitter.event().name("intent").data(intent.intent()));
            } catch (IOException e) {
                emitter.completeWithError(e);
                return;
            }
            // 流式生成回答，逐 chunk 推送；失败降级转人工
            buildSpec(sessionId, intent.intent()).user(request.message()).stream().content().subscribe(
                    chunk -> {
                        try {
                            emitter.send(SseEmitter.event().data(chunk));
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    },
                    err -> {
                        log.warn("stream error, sessionId={}", sessionId, err);
                        try {
                            emitter.send(SseEmitter.event().data(
                                    escalationService.escalate(sessionId, request.message(), "LLM_FAILURE")));
                            emitter.complete();
                        } catch (IOException ex) {
                            emitter.completeWithError(ex);
                        }
                    },
                    emitter::complete);
        });
        return emitter;
    }

    /** 低置信度澄清，超限转人工。 */
    private String clarifyReply(String sessionId, IntentResult intent, String message) {
        if (clarifyService.trackRound(sessionId)) {
            return StringUtils.hasText(intent.clarifyQuestion())
                    ? intent.clarifyQuestion()
                    : "抱歉，我没太理解您的意思，可以描述得再具体一些吗？";
        }
        return escalationService.escalate(sessionId, message, "LOW_CONFIDENCE");
    }

    /** 整段发送并结束（澄清/转人工/降级用）。 */
    private void sendAndComplete(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event().data(text));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private String normalizeSession(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId : "default";
    }
}
