package com.ailyrx.aichat.workflow;

import com.ailyrx.aichat.common.ErrorCodes;

/**
 * 确认/导出等运行状态相关错误，由全局异常处理转为业务码响应。
 */
public class WorkflowRunException extends RuntimeException {

    private final int code;

    public WorkflowRunException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static WorkflowRunException invalidConfirm(String message) {
        return new WorkflowRunException(ErrorCodes.CONFIRM_INVALID, message);
    }

    public static WorkflowRunException exportFailed(String message) {
        return new WorkflowRunException(ErrorCodes.EXPORT_FAILED, message);
    }
}
