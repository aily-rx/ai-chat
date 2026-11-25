package com.ailyrx.aichat.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存中的工作流运行记录。后端重启后清空，属于阶段二已知限制。
 */
public class WorkflowRunState {

    private final String runId;
    private final String traceId;
    private final WorkflowDocumentMeta document;
    private final String answer;
    private final String draft;
    private final Map<String, Object> toolResults;
    private final List<WorkflowNodeSnapshot> nodes;
    private final Instant createdAt;
    private final List<WorkflowHistoryEntry> history = new ArrayList<>();

    private WorkflowRunStatus status;
    private String errorCode;
    private String errorMessage;
    private String formToken;
    private Instant expiresAt;
    private WorkflowConfirmation confirmation;
    private Instant updatedAt;

    public WorkflowRunState(
            String runId,
            String traceId,
            WorkflowDocumentMeta document,
            String answer,
            String draft,
            Map<String, Object> toolResults,
            List<WorkflowNodeSnapshot> nodes,
            WorkflowRunStatus status,
            String formToken,
            Instant expiresAt) {
        this.runId = runId;
        this.traceId = traceId;
        this.document = document;
        this.answer = answer == null ? "" : answer;
        this.draft = draft == null ? "" : draft;
        this.toolResults = toolResults == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(toolResults));
        this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
        this.status = status;
        this.formToken = formToken;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.history.add(new WorkflowHistoryEntry(status.name(), null, traceId, this.createdAt));
    }

    public String getRunId() {
        return runId;
    }

    public String getTraceId() {
        return traceId;
    }

    public WorkflowDocumentMeta getDocument() {
        return document;
    }

    public String getAnswer() {
        return answer;
    }

    public String getDraft() {
        return draft;
    }

    public Map<String, Object> getToolResults() {
        return toolResults;
    }

    public List<WorkflowNodeSnapshot> getNodes() {
        return nodes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public WorkflowRunStatus getStatus() {
        return status;
    }

    public void setStatus(WorkflowRunStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getFormToken() {
        return formToken;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public WorkflowConfirmation getConfirmation() {
        return confirmation;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void fail(String errorCode, String errorMessage, String action, String traceId) {
        this.status = WorkflowRunStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.formToken = null;
        this.updatedAt = Instant.now();
        this.history.add(new WorkflowHistoryEntry(status.name(), action, traceId, this.updatedAt));
    }

    public void confirm(WorkflowRunStatus newStatus, WorkflowConfirmation newConfirmation, String action) {
        this.status = newStatus;
        this.confirmation = newConfirmation;
        this.formToken = null;
        this.expiresAt = null;
        this.updatedAt = Instant.now();
        this.history.add(new WorkflowHistoryEntry(newStatus.name(), action, newConfirmation.traceId(), this.updatedAt));
    }

    public WorkflowRunView toView() {
        return new WorkflowRunView(
                runId,
                status.name(),
                traceId,
                document,
                draft,
                answer,
                toolResults,
                nodes,
                errorCode,
                errorMessage,
                confirmation,
                List.copyOf(history),
                createdAt,
                updatedAt);
    }
}
