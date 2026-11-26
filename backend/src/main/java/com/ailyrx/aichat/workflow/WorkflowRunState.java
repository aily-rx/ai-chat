package com.ailyrx.aichat.workflow;

import com.ailyrx.aichat.rag.CitationItem;
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
    private final Map<String, String> inputs;
    private final Instant createdAt;
    private final List<WorkflowHistoryEntry> history = new ArrayList<>();

    private String answer;
    private String draft;
    private Map<String, Object> toolResults;
    private List<CitationItem> citations;
    private List<WorkflowNodeSnapshot> nodes;
    private int retryCount;
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
        this(runId, traceId, document, answer, draft, toolResults, List.of(), nodes, status,
                formToken, expiresAt, Map.of(), 0);
    }

    public WorkflowRunState(
            String runId,
            String traceId,
            WorkflowDocumentMeta document,
            String answer,
            String draft,
            Map<String, Object> toolResults,
            List<CitationItem> citations,
            List<WorkflowNodeSnapshot> nodes,
            WorkflowRunStatus status,
            String formToken,
            Instant expiresAt,
            Map<String, String> inputs,
            int retryCount) {
        this.runId = runId;
        this.traceId = traceId;
        this.document = document;
        this.inputs = inputs == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.answer = answer == null ? "" : answer;
        this.draft = draft == null ? "" : draft;
        this.toolResults = toolResults == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(toolResults));
        this.citations = citations == null ? List.of() : List.copyOf(citations);
        this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
        this.retryCount = Math.max(retryCount, 0);
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

    public Map<String, String> getInputs() {
        return inputs;
    }

    public int getRetryCount() {
        return retryCount;
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

    public List<CitationItem> getCitations() {
        return citations;
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

    /**
     * 首次驳回：记录驳回意见并进入重新生成状态，最多重试 1 次。
     */
    public void scheduleRetry(WorkflowConfirmation rejection, String traceId) {
        if (retryCount >= 1) {
            throw new IllegalStateException("工作流已使用重试次数");
        }
        this.status = WorkflowRunStatus.RETRYING;
        this.confirmation = rejection;
        this.retryCount += 1;
        this.formToken = null;
        this.expiresAt = null;
        this.updatedAt = Instant.now();
        this.history.add(new WorkflowHistoryEntry(status.name(), "reject", traceId, this.updatedAt));
    }

    /**
     * 重新生成后再次暂停，等待第二次人工确认。
     */
    public void completeRetry(
            String newAnswer,
            String newDraft,
            Map<String, Object> newToolResults,
            List<CitationItem> newCitations,
            List<WorkflowNodeSnapshot> newNodes,
            String newFormToken,
            Instant newExpiresAt,
            String traceId) {
        this.status = WorkflowRunStatus.WAITING_CONFIRM;
        this.answer = newAnswer == null ? "" : newAnswer;
        this.draft = newDraft == null ? "" : newDraft;
        this.toolResults = newToolResults == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(newToolResults));
        this.citations = newCitations == null ? List.of() : List.copyOf(newCitations);
        this.nodes = newNodes == null ? List.of() : List.copyOf(newNodes);
        this.formToken = newFormToken;
        this.expiresAt = newExpiresAt;
        this.errorCode = null;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
        this.history.add(new WorkflowHistoryEntry(status.name(), "retry", traceId, this.updatedAt));
    }

    public void failRetry(String errorCode, String errorMessage, String traceId) {
        this.status = WorkflowRunStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.formToken = null;
        this.expiresAt = null;
        this.updatedAt = Instant.now();
        this.history.add(new WorkflowHistoryEntry(status.name(), "retry", traceId, this.updatedAt));
    }

    public WorkflowRunView toView() {
        return new WorkflowRunView(
                runId,
                status.name(),
                retryCount,
                traceId,
                document,
                draft,
                answer,
                toolResults,
                citations,
                nodes,
                errorCode,
                errorMessage,
                confirmation,
                List.copyOf(history),
                createdAt,
                updatedAt);
    }
}
