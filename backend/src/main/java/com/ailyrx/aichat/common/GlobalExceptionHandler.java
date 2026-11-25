package com.ailyrx.aichat.common;

import com.ailyrx.aichat.config.TraceIdFilter;
import com.ailyrx.aichat.dify.DifyException;
import com.ailyrx.aichat.rag.RagValidationException;
import com.ailyrx.aichat.workflow.WorkflowRunException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCodes.INVALID_PARAMETER, message, resolveTraceId(request)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCodes.INVALID_PARAMETER, "请求体格式错误", resolveTraceId(request)));
    }

    @ExceptionHandler(RagValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleRagValidation(
            RagValidationException exception,
            HttpServletRequest request) {
        String traceId = resolveTraceId(request);
        log.warn("rag request rejected traceId={} reason={}", traceId, exception.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCodes.INVALID_PARAMETER, exception.getMessage(), traceId));
    }

    @ExceptionHandler(DifyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDifyUnavailable(
            DifyException exception,
            HttpServletRequest request) {
        String traceId = resolveTraceId(request);
        log.warn("dify unavailable traceId={} upstreamStatus={} message={}",
                traceId, exception.upstreamStatus(), exception.getMessage());
        return ResponseEntity.ok()
                .body(ApiResponse.error(
                        ErrorCodes.DIFY_UNAVAILABLE, exception.getMessage(), traceId));
    }

    @ExceptionHandler(WorkflowRunException.class)
    public ResponseEntity<ApiResponse<Void>> handleWorkflowRun(
            WorkflowRunException exception,
            HttpServletRequest request) {
        String traceId = resolveTraceId(request);
        log.warn("workflow run rejected traceId={} code={} message={}",
                traceId, exception.getCode(), exception.getMessage());
        return ResponseEntity.ok()
                .body(ApiResponse.error(exception.getCode(), exception.getMessage(), traceId));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(
                        ErrorCodes.INVALID_PARAMETER, "文件大小不能超过20MB", resolveTraceId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        String traceId = resolveTraceId(request);
        log.error("unhandled server error traceId={}", traceId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(5000, "服务端内部错误", traceId));
    }

    private String resolveTraceId(HttpServletRequest request) {
        Object attribute = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (attribute instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
