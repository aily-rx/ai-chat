package com.ailyrx.aichat.workflow;

import java.util.Map;

public record DifyWorkflowResult(
        String runId,
        String status,
        Map<String, Object> outputs,
        boolean streamedText,
        DifyHumanInput humanInput) {

    public DifyWorkflowResult(String runId, String status, Map<String, Object> outputs, boolean streamedText) {
        this(runId, status, outputs, streamedText, null);
    }

    public boolean waitingForHumanInput() {
        return humanInput != null;
    }
}
