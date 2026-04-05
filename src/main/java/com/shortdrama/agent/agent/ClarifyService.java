package com.shortdrama.agent.agent;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮澄清：低置信度时向用户提问补齐槽位。
 * 记录每个会话的澄清轮次，超过上限强制转人工，防止与用户无限循环问答。
 */
@Service
public class ClarifyService {

    /** 每会话最大澄清轮数。 */
    private static final int MAX_CLARIFY_ROUNDS = 2;

    private final ConcurrentHashMap<String, Integer> clarifyRounds = new ConcurrentHashMap<>();

    /** 记录一轮澄清；返回是否还能继续澄清（超限则 false）。 */
    public boolean trackRound(String sessionId) {
        int rounds = clarifyRounds.merge(sessionId, 1, Integer::sum);
        if (rounds > MAX_CLARIFY_ROUNDS) {
            clarifyRounds.remove(sessionId);
            return false;
        }
        return true;
    }

    /** 会话结束（意图已满足/转人工）时清除计数。 */
    public void clear(String sessionId) {
        clarifyRounds.remove(sessionId);
    }
}
