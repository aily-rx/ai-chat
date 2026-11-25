package com.ailyrx.aichat.workflow;

import com.ailyrx.aichat.common.ErrorCodes;
import com.ailyrx.aichat.dify.DifyException;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 编排 Dify Workflow SSE：节点事件、增量文本、暂停确认与 done/error 终止事件。
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);
    private static final String DRAFT_MARKER_REVIEW = "草稿如下，未做任何修改：\n";
    private static final String DRAFT_MARKER_DEFAULT = "草稿如下：\n";
    private static final String DRAFT_MARKER_FALLBACK = "草稿：\n";

    private final DifyWorkflowClient difyWorkflowClient;
    private final WorkflowRunStore runStore;

    public WorkflowService(DifyWorkflowClient difyWorkflowClient, WorkflowRunStore runStore) {
        this.difyWorkflowClient = difyWorkflowClient;
        this.runStore = runStore;
    }

    public void stream(
            String input,
            String draft,
            String traceId,
            SseEmitter emitter,
            AtomicBoolean closed) {
        AtomicBoolean contentSent = new AtomicBoolean(false);
        StringBuilder answerBuffer = new StringBuilder();
        Map<String, WorkflowNodeSnapshot> nodes = new LinkedHashMap<>();
        Map<String, Object> toolResults = new LinkedHashMap<>();
        WorkflowDocumentMeta document = documentMeta(input);

        try {
            DifyWorkflowResult result = difyWorkflowClient.run(
                    buildInputs(input, draft),
                    event -> {
                        snapshot(nodes, event);
                        captureToolResult(toolResults, event);
                        send(emitter, closed, "node", nodePayload(event));
                    },
                    delta -> {
                        contentSent.set(true);
                        answerBuffer.append(delta);
                        send(emitter, closed, "message", Map.of("delta", delta));
                    },
                    closed::get,
                    traceId);

            if (!contentSent.get()) {
                String answer = extractAnswer(result.outputs());
                if ((answer == null || answer.isBlank()) && result.waitingForHumanInput()) {
                    answer = result.humanInput().formContent();
                }
                if (answer != null && !answer.isBlank()) {
                    contentSent.set(true);
                    answerBuffer.append(answer);
                    send(emitter, closed, "message", Map.of("delta", answer));
                }
            }

            String answer = answerBuffer.toString();
            String draftContent = extractDraft(answer);
            String runId = result.runId() == null ? traceId : result.runId();
            List<WorkflowNodeSnapshot> nodeList = new ArrayList<>(nodes.values());

            if (result.waitingForHumanInput()) {
                DifyHumanInput humanInput = result.humanInput();
                WorkflowRunState state = new WorkflowRunState(
                        runId,
                        traceId,
                        document,
                        answer,
                        draftContent,
                        toolResults,
                        nodeList,
                        WorkflowRunStatus.WAITING_CONFIRM,
                        humanInput.formToken(),
                        humanInput.expiresAt());
                runStore.save(state);

                if (!closed.get()) {
                    Map<String, Object> done = new LinkedHashMap<>();
                    done.put("runId", runId);
                    done.put("status", WorkflowRunStatus.WAITING_CONFIRM.name());
                    done.put("traceId", traceId);
                    send(emitter, closed, "done", done);
                }
                log.info("workflow waiting confirm traceId={} runId={}", traceId, runId);
                complete(emitter);
                return;
            }

            WorkflowRunState state = new WorkflowRunState(
                    runId,
                    traceId,
                    document,
                    answer,
                    draftContent,
                    toolResults,
                    nodeList,
                    WorkflowRunStatus.SUCCEEDED,
                    null,
                    null);
            runStore.save(state);

            if (!closed.get()) {
                Map<String, Object> done = new LinkedHashMap<>();
                if (result.runId() != null) {
                    done.put("runId", result.runId());
                }
                done.put("status", result.status());
                done.put("traceId", traceId);
                send(emitter, closed, "done", done);
            }
            complete(emitter);
        } catch (DifyException exception) {
            log.warn("workflow failed traceId={} upstreamStatus={} message={}",
                    traceId, exception.upstreamStatus(), exception.getMessage());
            sendError(emitter, closed, traceId, exception.getMessage());
            complete(emitter);
        } catch (RuntimeException exception) {
            log.error("workflow unhandled error traceId={}", traceId, exception);
            sendError(emitter, closed, traceId, "工作流执行失败，请稍后重试");
            complete(emitter);
        }
    }

    Map<String, String> buildInputs(String input, String draft) {
        WorkflowDocumentMeta document = documentMeta(input);
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("task", document.title());
        inputs.put("draft", draft == null ? "" : draft);
        inputs.put("title", document.title());
        inputs.put("doc_type", document.docType());
        inputs.put("recipient", document.recipient());
        inputs.put("signature", document.signature());
        inputs.put("date", document.date());
        return inputs;
    }

    private WorkflowDocumentMeta documentMeta(String input) {
        String task = input == null ? "" : input.strip();
        return new WorkflowDocumentMeta(
                deriveTitle(task),
                "通知",
                "各部门：",
                "AI 公文助手",
                LocalDate.now().toString());
    }

    private String deriveTitle(String task) {
        String title = task.isEmpty() ? "关于有关工作的通知" : task;
        return title.length() <= 50 ? title : title.substring(0, 50);
    }

    private void snapshot(Map<String, WorkflowNodeSnapshot> nodes, WorkflowNodeEvent event) {
        if (event.nodeId() == null) {
            return;
        }
        nodes.put(event.nodeId(), new WorkflowNodeSnapshot(
                event.nodeId(), event.name(), event.nodeType(), event.status()));
    }

    private void captureToolResult(Map<String, Object> toolResults, WorkflowNodeEvent event) {
        if (event.nodeId() == null || event.outputs() == null || event.outputs().isEmpty()) {
            return;
        }
        String key = switch (event.nodeId()) {
            case "format_parse", "format_fallback" -> "format";
            case "terms_parse", "terms_fallback" -> "terms";
            default -> null;
        };
        if (key == null) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>(event.outputs());
        entry.put("nodeId", event.nodeId());
        entry.put("status", event.status());
        if (event.nodeId().endsWith("fallback")) {
            entry.put("fallback", true);
        }
        toolResults.put(key, entry);
    }

    private Map<String, Object> nodePayload(WorkflowNodeEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (event.nodeId() != null) {
            payload.put("nodeId", event.nodeId());
        }
        if (event.name() != null) {
            payload.put("name", event.name());
        }
        if (event.nodeType() != null) {
            payload.put("type", event.nodeType());
        }
        payload.put("status", event.status());
        return payload;
    }

    static String extractDraft(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        for (String marker : List.of(DRAFT_MARKER_REVIEW, DRAFT_MARKER_DEFAULT, DRAFT_MARKER_FALLBACK)) {
            int index = answer.indexOf(marker);
            if (index >= 0) {
                return answer.substring(index + marker.length()).strip();
            }
        }
        return answer.strip();
    }

    private String extractAnswer(Map<String, Object> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return null;
        }
        Object answer = outputs.get("answer");
        if (answer instanceof String text) {
            return text;
        }
        for (Object value : outputs.values()) {
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private void send(SseEmitter emitter, AtomicBoolean closed, String event, Object data) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException exception) {
            closed.set(true);
            log.debug("workflow sse client disconnected");
        }
    }

    private void sendError(SseEmitter emitter, AtomicBoolean closed, String traceId, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", ErrorCodes.WORKFLOW_FAILED);
        payload.put("message", message);
        payload.put("traceId", traceId);
        send(emitter, closed, "error", payload);
    }

    private void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // 客户端已断开时无需再次结束。
        }
    }
}
