package com.ailyrx.aichat.workflow;

import java.util.Map;

public record WorkflowNodeEvent(
        String nodeId,
        String name,
        String nodeType,
        String status,
        Map<String, Object> outputs) {

    public WorkflowNodeEvent(String nodeId, String name, String nodeType, String status) {
        this(nodeId, name, nodeType, status, Map.of());
    }
}
