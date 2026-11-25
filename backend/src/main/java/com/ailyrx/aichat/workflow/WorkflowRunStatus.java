package com.ailyrx.aichat.workflow;

/**
 * 工作流运行状态。人工确认阶段停在 WAITING_CONFIRM。
 */
public enum WorkflowRunStatus {
    RUNNING,
    WAITING_CONFIRM,
    SUCCEEDED,
    FAILED,
    REJECTED
}
