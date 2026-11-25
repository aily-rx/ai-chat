package com.ailyrx.aichat.workflow;

import com.ailyrx.aichat.dify.DifyException;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 运行状态查询、人工确认和导出前置校验。
 * 确认串行化，保证同一表单不会被重复提交。
 */
@Service
public class WorkflowRunService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunService.class);
    private static final Set<String> ACTIONS = Set.of("approve", "modify", "reject");

    private final WorkflowRunStore runStore;
    private final DifyWorkflowClient difyWorkflowClient;

    public WorkflowRunService(WorkflowRunStore runStore, DifyWorkflowClient difyWorkflowClient) {
        this.runStore = runStore;
        this.difyWorkflowClient = difyWorkflowClient;
    }

    public synchronized WorkflowRunView view(String runId) {
        WorkflowRunState state = requireRun(runId);
        expireIfNeeded(state);
        return state.toView();
    }

    public synchronized WorkflowRunView confirm(
            String runId, String action, String content, String traceId) {
        WorkflowRunState state = requireRun(runId);
        expireIfNeeded(state);

        String normalizedAction = action == null ? "" : action.strip().toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(normalizedAction)) {
            throw WorkflowRunException.invalidConfirm("确认动作不合法");
        }
        if (state.getStatus() == WorkflowRunStatus.FAILED && state.getErrorMessage() != null) {
            throw WorkflowRunException.invalidConfirm(state.getErrorMessage());
        }
        if (state.getStatus() != WorkflowRunStatus.WAITING_CONFIRM) {
            throw WorkflowRunException.invalidConfirm("当前状态不可确认，请刷新后重试");
        }

        String approvedContent = state.getDraft();
        if ("modify".equals(normalizedAction)) {
            if (content == null || content.isBlank()) {
                throw WorkflowRunException.invalidConfirm("修改内容不能为空");
            }
            approvedContent = content.strip();
        }

        if (state.getFormToken() != null && !state.getFormToken().isBlank()) {
            try {
                difyWorkflowClient.submitHumanInput(
                        state.getFormToken(), normalizedAction, approvedContent, state.getTraceId());
            } catch (DifyException exception) {
                log.warn("workflow confirm submit failed runId={} traceId={} upstreamStatus={} message={}",
                        runId, traceId, exception.upstreamStatus(), exception.getMessage());
                throw WorkflowRunException.invalidConfirm(exception.getMessage());
            }
        } else {
            log.warn("workflow confirm without form token runId={} traceId={}", runId, traceId);
        }

        WorkflowRunStatus newStatus = "reject".equals(normalizedAction)
                ? WorkflowRunStatus.REJECTED
                : WorkflowRunStatus.SUCCEEDED;
        WorkflowConfirmation confirmation = new WorkflowConfirmation(
                normalizedAction, approvedContent, traceId, Instant.now());
        state.confirm(newStatus, confirmation, normalizedAction);
        log.info("workflow confirmed runId={} traceId={} action={}",
                runId, traceId, normalizedAction);
        return state.toView();
    }

    public synchronized WorkflowRunState requireExportable(String runId) {
        WorkflowRunState state = runStore.find(runId)
                .orElseThrow(() -> WorkflowRunException.exportFailed("运行记录不存在或已失效"));
        expireIfNeeded(state);
        if (state.getStatus() != WorkflowRunStatus.SUCCEEDED) {
            throw WorkflowRunException.exportFailed("未确认或当前状态不可导出");
        }
        return state;
    }

    private WorkflowRunState requireRun(String runId) {
        return runStore.find(runId)
                .orElseThrow(() -> WorkflowRunException.invalidConfirm("运行记录不存在或已失效"));
    }

    private void expireIfNeeded(WorkflowRunState state) {
        if (state.getStatus() != WorkflowRunStatus.WAITING_CONFIRM || state.getExpiresAt() == null) {
            return;
        }
        if (state.getExpiresAt().isBefore(Instant.now())) {
            state.fail("5005", "确认已过期，请重新发起任务", "expire", state.getTraceId());
            log.info("workflow confirmation expired runId={} traceId={}",
                    state.getRunId(), state.getTraceId());
        }
    }
}
