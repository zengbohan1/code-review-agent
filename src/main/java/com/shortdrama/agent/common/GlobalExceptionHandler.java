package com.shortdrama.agent.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/**
 * 全局异常处理：业务规则错误（退款超限/双确认缺失等）转成 400 返回，
 * 未预期异常记日志并返回 500 兜底（不向客户端泄漏堆栈）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBusiness(IllegalArgumentException e) {
        return Result.fail(e.getMessage());
    }

    /**
     * SSE 流式连接超时（客户端断连/长时间无数据）：属正常交互，不打错误日志。
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    @ResponseStatus(HttpStatus.REQUEST_TIMEOUT)
    public void handleAsyncTimeout(AsyncRequestTimeoutException e) {
        log.debug("sse request timeout: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return Result.fail("系统繁忙，请稍后再试");
    }
}
