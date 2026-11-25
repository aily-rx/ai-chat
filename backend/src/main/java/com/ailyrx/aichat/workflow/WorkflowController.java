package com.ailyrx.aichat.workflow;

import com.ailyrx.aichat.config.TraceIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    private static final long SSE_TIMEOUT_MILLIS = 180_000L;

    private final WorkflowService workflowService;
    private final ThreadPoolTaskExecutor chatExecutor;

    public WorkflowController(
            WorkflowService workflowService,
            @Qualifier("chatExecutor") ThreadPoolTaskExecutor chatExecutor) {
        this.workflowService = workflowService;
        this.chatExecutor = chatExecutor;
    }

    @PostMapping(value = "/run/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(
            @Valid @RequestBody WorkflowRunRequest request,
            @RequestAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE) String traceId,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        AtomicBoolean closed = new AtomicBoolean(false);

        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(exception -> closed.set(true));

        chatExecutor.execute(() -> workflowService.stream(
                request.input(),
                request.draft(),
                traceId,
                emitter,
                closed));

        return emitter;
    }
}
