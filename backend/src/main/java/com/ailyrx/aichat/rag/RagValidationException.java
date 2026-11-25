package com.ailyrx.aichat.rag;

/**
 * RAG 请求参数校验失败，由全局异常处理映射为 HTTP 400 + code 4000。
 */
public class RagValidationException extends RuntimeException {

    public RagValidationException(String message) {
        super(message);
    }
}
