package com.ailyrx.aichat.workflow;

import com.ailyrx.aichat.rag.CitationItem;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GET /api/workflow/{runId} 的响应视图，刻意不包含 Dify form_token。
 */
public record WorkflowRunView(
        String runId,
        String status,
        int retryCount,
        String traceId,
        WorkflowDocumentMeta document,
        String draft,
        String answer,
        Map<String, Object> toolResults,
        List<CitationItem> citations,
        List<WorkflowNodeSnapshot> nodes,
        String errorCode,
        String errorMessage,
        WorkflowConfirmation confirmation,
        List<WorkflowHistoryEntry> history,
        Instant createdAt,
        Instant updatedAt) {
}
