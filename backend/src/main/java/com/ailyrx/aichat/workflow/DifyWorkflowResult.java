package com.ailyrx.aichat.workflow;

import java.util.Map;

public record DifyWorkflowResult(
        String runId,
        String status,
        Map<String, Object> outputs,
        boolean streamedText) {
}
