package com.ailyrx.aichat.dify;

/**
 * Dify 调用失败的统一异常；message 已脱敏，可直接返回给前端。
 */
public class DifyException extends RuntimeException {

    private final int upstreamStatus;

    public DifyException(String message, int upstreamStatus) {
        super(message);
        this.upstreamStatus = upstreamStatus;
    }

    public DifyException(String message, int upstreamStatus, Throwable cause) {
        super(message, cause);
        this.upstreamStatus = upstreamStatus;
    }

    public int upstreamStatus() {
        return upstreamStatus;
    }
}
