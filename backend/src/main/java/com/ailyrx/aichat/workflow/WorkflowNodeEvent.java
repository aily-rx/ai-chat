package com.ailyrx.aichat.workflow;

public record WorkflowNodeEvent(
        String nodeId,
        String name,
        String nodeType,
        String status) {
}
