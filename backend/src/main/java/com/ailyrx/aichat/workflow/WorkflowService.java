package com.ailyrx.aichat.workflow;

import com.ailyrx.aichat.common.ErrorCodes;
import com.ailyrx.aichat.dify.DifyException;
import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 编排 Dify Workflow SSE：节点事件、增量文本、done/error 终止事件。
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final DifyWorkflowClient difyWorkflowClient;

    public WorkflowService(DifyWorkflowClient difyWorkflowClient) {
        this.difyWorkflowClient = difyWorkflowClient;
    }

    public void stream(
            String input,
            String draft,
            String traceId,
            SseEmitter emitter,
            AtomicBoolean closed) {
        AtomicBoolean contentSent = new AtomicBoolean(false);
        try {
            DifyWorkflowResult result = difyWorkflowClient.run(
                    buildInputs(input, draft),
                    event -> send(emitter, closed, "node", nodePayload(event)),
                    delta -> {
                        contentSent.set(true);
                        send(emitter, closed, "message", Map.of("delta", delta));
                    },
                    closed::get,
                    traceId);

            if (!contentSent.get()) {
                String answer = extractAnswer(result.outputs());
                if (answer != null && !answer.isBlank()) {
                    contentSent.set(true);
                    send(emitter, closed, "message", Map.of("delta", answer));
                }
            }

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
        String task = input == null ? "" : input.strip();
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("task", task);
        inputs.put("draft", draft == null ? "" : draft);
        inputs.put("title", deriveTitle(task));
        inputs.put("doc_type", "通知");
        inputs.put("recipient", "各部门：");
        inputs.put("signature", "AI 公文助手");
        inputs.put("date", LocalDate.now().toString());
        return inputs;
    }

    private String deriveTitle(String task) {
        String title = task.isEmpty() ? "关于有关工作的通知" : task;
        return title.length() <= 50 ? title : title.substring(0, 50);
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
