package com.ailyrx.aichat.workflow;

import java.time.Instant;

public record WorkflowHistoryEntry(
        String status,
        String action,
        String traceId,
        Instant at) {
}
