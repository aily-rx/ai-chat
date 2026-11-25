package com.ailyrx.aichat.workflow;

public record WorkflowNodeSnapshot(
        String nodeId,
        String name,
        String type,
        String status) {
}
