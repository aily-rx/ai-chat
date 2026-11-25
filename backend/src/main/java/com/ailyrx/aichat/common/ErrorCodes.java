package com.ailyrx.aichat.common;

/**
 * 业务错误码统一入口，避免接口实现中散落魔法数字。
 */
public final class ErrorCodes {

    public static final int INVALID_PARAMETER = 4000;
    public static final int MODEL_CALL_FAILED = 5001;
    public static final int DIFY_UNAVAILABLE = 5002;
    public static final int EXTRACTION_PARSE_FAILED = 5003;
    public static final int WORKFLOW_FAILED = 5004;
    public static final int CONFIRM_INVALID = 5005;
    public static final int EXPORT_FAILED = 5006;

    private ErrorCodes() {
    }
}
