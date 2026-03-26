package com.shortdrama.agent.common;

/**
 * 统一接口返回结构。
 *
 * @param code 0=成功，非 0=业务错误
 */
public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(1, message, null);
    }
}
