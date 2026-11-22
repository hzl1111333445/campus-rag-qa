package com.campus.rag.service;

/**
 * 登录/令牌相关的业务异常。
 *
 * <p>单独一个类型的目的：让"密码错"这类情况能干净地映射成 HTTP 401，
 * 而不是被当成普通参数错误（400）或被兜底成 500。
 */
public class AuthException extends RuntimeException {

    public AuthException(String message) {
        super(message);
    }
}
