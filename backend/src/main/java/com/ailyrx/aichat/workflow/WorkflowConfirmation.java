package com.ailyrx.aichat.workflow;

import java.time.Instant;

public record WorkflowConfirmation(
        String action,
        String content,
        String traceId,
        Instant confirmedAt) {
}
