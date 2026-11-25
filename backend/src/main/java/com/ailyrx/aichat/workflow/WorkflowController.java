package com.ailyrx.aichat.workflow;

import com.ailyrx.aichat.common.ApiResponse;
import com.ailyrx.aichat.config.TraceIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    private static final long SSE_TIMEOUT_MILLIS = 180_000L;

    private final WorkflowService workflowService;
    private final WorkflowRunService workflowRunService;
    private final WorkflowExportService workflowExportService;
    private final ThreadPoolTaskExecutor chatExecutor;

    public WorkflowController(
            WorkflowService workflowService,
            WorkflowRunService workflowRunService,
            WorkflowExportService workflowExportService,
            @Qualifier("chatExecutor") ThreadPoolTaskExecutor chatExecutor) {
        this.workflowService = workflowService;
        this.workflowRunService = workflowRunService;
        this.workflowExportService = workflowExportService;
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

    @GetMapping("/{runId}")
    public ApiResponse<WorkflowRunView> get(
            @PathVariable String runId,
            @RequestAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE) String traceId) {
        return ApiResponse.ok(workflowRunService.view(runId), traceId);
    }

    @PostMapping("/{runId}/confirm")
    public ApiResponse<WorkflowRunView> confirm(
            @PathVariable String runId,
            @Valid @RequestBody WorkflowConfirmRequest request,
            @RequestAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE) String traceId) {
        return ApiResponse.ok(
                workflowRunService.confirm(runId, request.action(), request.content(), traceId),
                traceId);
    }

    @GetMapping("/{runId}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable String runId,
            @RequestParam(defaultValue = "md") String format,
            @RequestAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE) String traceId) {
        WorkflowRunState state = workflowRunService.requireExportable(runId);
        ExportFile file = workflowExportService.export(state, format);
        String disposition = ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
