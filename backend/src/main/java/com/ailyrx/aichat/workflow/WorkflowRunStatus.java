package com.ailyrx.aichat.workflow;

/**
 * 工作流运行状态。人工确认阶段停在 WAITING_CONFIRM；
 * 首次驳回进入 RETRYING，重新生成后最多再确认一次。
 */
public enum WorkflowRunStatus {
    RUNNING,
    WAITING_CONFIRM,
    RETRYING,
    SUCCEEDED,
    FAILED,
    REJECTED
}
