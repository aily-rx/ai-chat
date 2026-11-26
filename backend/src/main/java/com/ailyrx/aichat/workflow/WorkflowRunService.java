package com.ailyrx.aichat.workflow;

import com.ailyrx.aichat.dify.DifyException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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

    private static final int MAX_RETRY_COUNT = 1;
    private static final int MAX_TASK_LENGTH = 2000;

    private final WorkflowRunStore runStore;
    private final DifyWorkflowClient difyWorkflowClient;
    private final WorkflowRetryExecutor retryExecutor;

    public WorkflowRunService(
            WorkflowRunStore runStore,
            DifyWorkflowClient difyWorkflowClient,
            WorkflowRetryExecutor retryExecutor) {
        this.runStore = runStore;
        this.difyWorkflowClient = difyWorkflowClient;
        this.retryExecutor = retryExecutor;
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

        String submittedContent = state.getDraft();
        String rejectionFeedback = "";
        if ("modify".equals(normalizedAction)) {
            if (content == null || content.isBlank()) {
                throw WorkflowRunException.invalidConfirm("修改内容不能为空");
            }
            submittedContent = content.strip();
        } else if ("reject".equals(normalizedAction)) {
            rejectionFeedback = content == null ? "" : content.strip();
            if (!rejectionFeedback.isBlank()) {
                submittedContent = rejectionFeedback;
            }
        }

        if (state.getFormToken() != null && !state.getFormToken().isBlank()) {
            try {
                difyWorkflowClient.submitHumanInput(
                        state.getFormToken(), normalizedAction, submittedContent, state.getTraceId());
            } catch (DifyException exception) {
                log.warn("workflow confirm submit failed runId={} traceId={} upstreamStatus={} message={}",
                        runId, traceId, exception.upstreamStatus(), exception.getMessage());
                throw WorkflowRunException.invalidConfirm(exception.getMessage());
            }
        } else {
            log.warn("workflow confirm without form token runId={} traceId={}", runId, traceId);
        }

        WorkflowConfirmation confirmation = new WorkflowConfirmation(
                normalizedAction, submittedContent, traceId, Instant.now());

        if ("reject".equals(normalizedAction) && state.getRetryCount() < MAX_RETRY_COUNT) {
            Map<String, String> retryInputs = retryInputs(state, rejectionFeedback);
            state.scheduleRetry(confirmation, traceId);
            try {
                retryExecutor.submit(() -> retry(runId, retryInputs, traceId));
            } catch (RuntimeException exception) {
                log.error("workflow retry scheduling failed runId={} traceId={}",
                        runId, traceId, exception);
                state.failRetry("5004", "重新生成任务提交失败，请重新发起任务", traceId);
            }
            log.info("workflow rejected, retry scheduled runId={} traceId={} retryCount={}",
                    runId, traceId, state.getRetryCount());
            return state.toView();
        }

        WorkflowRunStatus newStatus = "reject".equals(normalizedAction)
                ? WorkflowRunStatus.REJECTED
                : WorkflowRunStatus.SUCCEEDED;
        state.confirm(newStatus, confirmation, normalizedAction);
        log.info("workflow confirmed runId={} traceId={} action={}",
                runId, traceId, normalizedAction);
        return state.toView();
    }

    private Map<String, String> retryInputs(WorkflowRunState state, String feedback) {
        Map<String, String> inputs = new LinkedHashMap<>(state.getInputs());
        if (inputs.isEmpty()) {
            WorkflowDocumentMeta document = state.getDocument();
            inputs.put("task", document.title());
            inputs.put("draft", "");
            inputs.put("title", document.title());
            inputs.put("doc_type", document.docType());
            inputs.put("recipient", document.recipient());
            inputs.put("signature", document.signature());
            inputs.put("date", document.date());
        }
        // 非空 draft 会让 S8 工作流跳过检索与生成，因此重试必须置空，
        // 把上一版草稿和驳回意见放入 task，强制从生成节点重新起草。
        String previousDraft = state.getDraft().strip();
        inputs.put("draft", "");
        String baseTask = inputs.getOrDefault("task", state.getDocument().title()).strip();
        String feedbackText = feedback == null ? "" : feedback.strip();
        String feedbackBlock = feedbackText.isBlank() ? "" : "\n驳回意见：" + feedbackText;
        String draftHeader = previousDraft.isBlank()
                ? ""
                : "\n上一版草稿（请重新生成并吸收其中有效内容）：\n";
        int budget = MAX_TASK_LENGTH - baseTask.length() - feedbackBlock.length() - draftHeader.length();
        String draftContext = "";
        if (budget > 0 && !previousDraft.isBlank()) {
            draftContext = previousDraft.length() <= budget
                    ? previousDraft
                    : previousDraft.substring(0, budget);
        }
        String combined = baseTask + feedbackBlock + draftHeader + draftContext;
        inputs.put("task", combined.length() <= MAX_TASK_LENGTH
                ? combined
                : combined.substring(0, MAX_TASK_LENGTH));
        return inputs;
    }

    /**
     * 驳回后按同一运行记录重新生成：沿用原输入并清空 draft，
     * 上一版草稿与驳回意见写入任务描述，强制重新检索和生成。
     * Dify 再次暂停时恢复为 WAITING_CONFIRM。
     */
    private void retry(String runId, Map<String, String> inputs, String traceId) {
        WorkflowEventCollector collector = new WorkflowEventCollector();
        StringBuilder answerBuffer = new StringBuilder();
        try {
            DifyWorkflowResult result = difyWorkflowClient.run(
                    inputs,
                    collector::accept,
                    answerBuffer::append,
                    () -> false,
                    traceId);
            String answer = answerBuffer.toString();
            if (answer.isBlank()) {
                answer = WorkflowService.extractAnswer(result.outputs());
            }
            if ((answer == null || answer.isBlank()) && result.waitingForHumanInput()) {
                answer = result.humanInput().formContent();
            }
            synchronized (this) {
                WorkflowRunState state = runStore.find(runId).orElse(null);
                if (state == null || state.getStatus() != WorkflowRunStatus.RETRYING) {
                    log.warn("workflow retry result ignored runId={} traceId={}", runId, traceId);
                    return;
                }
                if (!result.waitingForHumanInput()) {
                    state.failRetry("5004", "重新生成未返回待确认草稿，请重新发起任务", traceId);
                    log.warn("workflow retry finished without confirm runId={} traceId={}", runId, traceId);
                    return;
                }
                state.completeRetry(
                        answer,
                        WorkflowService.extractDraft(answer),
                        collector.toolResults(),
                        collector.citations(),
                        collector.nodes(),
                        result.humanInput().formToken(),
                        result.humanInput().expiresAt(),
                        traceId);
                log.info("workflow retry waiting confirm runId={} traceId={} retryCount={} nodes={}",
                        runId, traceId, state.getRetryCount(), collector.nodes().size());
            }
        } catch (DifyException exception) {
            log.warn("workflow retry failed runId={} traceId={} upstreamStatus={} message={}",
                    runId, traceId, exception.upstreamStatus(), exception.getMessage());
            failRetry(runId, exception.getMessage(), traceId);
        } catch (RuntimeException exception) {
            log.error("workflow retry unhandled error runId={} traceId={}", runId, traceId, exception);
            failRetry(runId, "重新生成失败，请重新发起任务", traceId);
        }
    }

    private synchronized void failRetry(String runId, String message, String traceId) {
        runStore.find(runId).ifPresent(state -> {
            if (state.getStatus() == WorkflowRunStatus.RETRYING) {
                state.failRetry("5004", message, traceId);
            }
        });
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
