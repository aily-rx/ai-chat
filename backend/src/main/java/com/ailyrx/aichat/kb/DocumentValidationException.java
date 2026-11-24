package com.ailyrx.aichat.kb;

/**
 * 上传参数校验失败，由控制器映射为 HTTP 400 + code 4000。
 */
public class DocumentValidationException extends RuntimeException {

    public DocumentValidationException(String message) {
        super(message);
    }
}
