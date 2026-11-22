package com.campus.rag.service;

/**
 * 访问了不属于自己的资源（会话、消息等）。
 *
 * <p>语义上对应 HTTP 403；但在"按 id 访问别人的会话"这种场景，
 * Controller 会把它转成 <b>404 + "会话不存在"</b>，避免泄露"这个 id 是存在的"。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
