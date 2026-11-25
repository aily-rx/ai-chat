package com.ailyrx.aichat.rag;

import com.ailyrx.aichat.common.ErrorCodes;
import com.ailyrx.aichat.dify.DifyException;
import com.ailyrx.aichat.dify.DifyProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final List<String> REFUSAL_MARKERS = List.of(
            "资料不足",
            "无法回答该问题",
            "无法回答您的问题",
            "未找到相关",
            "没有找到相关");

    private final DifyProperties datasetProperties;
    private final DifyChatProperties chatProperties;
    private final DifyChatClient difyChatClient;
    private final CitationCollector citationCollector;

    public RagService(
            DifyProperties datasetProperties,
            DifyChatProperties chatProperties,
            DifyChatClient difyChatClient,
            CitationCollector citationCollector) {
        this.datasetProperties = datasetProperties;
        this.chatProperties = chatProperties;
        this.difyChatClient = difyChatClient;
        this.citationCollector = citationCollector;
    }

    /**
     * 校验并归一化 datasetId，阻止前端指定白名单以外的知识库。
     */
    public String resolveDatasetId(String requested) {
        String datasetId = blankToEmpty(requested);
        if (datasetId.isEmpty()) {
            datasetId = firstNonBlank(
                    datasetProperties.defaultDatasetId(),
                    datasetProperties.datasetBId());
            if (datasetId.isEmpty()) {
                throw new DifyException("知识库问答服务未配置，请联系管理员", 0);
            }
        }

        boolean isA = datasetId.equals(blankToEmpty(datasetProperties.datasetAId()));
        boolean isB = datasetId.equals(blankToEmpty(datasetProperties.datasetBId()));
        if (!isA && !isB) {
            throw new RagValidationException("datasetId 只支持 A 或 B 知识库");
        }
        return datasetId;
    }

    public void stream(
            String question,
            String datasetId,
            String traceId,
            SseEmitter emitter,
            AtomicBoolean closed) {
        long startedAt = System.nanoTime();
        StringBuilder answer = new StringBuilder();

        try {
            String appKey = appKeyFor(datasetId);
            DifyChatResult result = difyChatClient.streamChat(
                    appKey,
                    question,
                    delta -> {
                        answer.append(delta);
                        sendDelta(emitter, delta, closed);
                    },
                    closed::get,
                    traceId);

            if (closed.get()) {
                return;
            }

            String answerText = answer.toString();
            if (answerText.isBlank()) {
                log.warn("rag empty answer traceId={} datasetId={}", traceId, datasetId);
                sendErrorAndComplete(emitter, traceId, ErrorCodes.DIFY_UNAVAILABLE,
                        "知识库未能生成回答，请稍后重试");
                return;
            }

            boolean refusal = isRefusal(answerText);
            List<CitationItem> citations = refusal
                    ? List.of()
                    : citationCollector.collect(result.resources());
            if (!refusal && citations.isEmpty()) {
                log.warn("rag answer without citations traceId={} datasetId={} chars={}",
                        traceId, datasetId, answerText.length());
                sendErrorAndComplete(emitter, traceId, ErrorCodes.DIFY_UNAVAILABLE,
                        "本次引用不可用，请稍后重试");
                return;
            }

            sendEvent(emitter, SseEmitter.event()
                    .name("citations")
                    .data(new CitationPayload(citations)), closed);
            if (closed.get()) {
                return;
            }

            String runId = result.messageId() == null || result.messageId().isBlank()
                    ? traceId
                    : result.messageId();
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            sendEvent(emitter, SseEmitter.event()
                    .name("done")
                    .data(Map.of(
                            "runId", runId,
                            "traceId", traceId,
                            "elapsedMs", elapsedMs)), closed);

            log.info("rag stream completed traceId={} datasetId={} runId={} chars={} citations={} refusal={} elapsedMs={}",
                    traceId, datasetId, runId, answerText.length(), citations.size(), refusal, elapsedMs);
            safeComplete(emitter);
        } catch (SseSendException exception) {
            log.debug("client disconnected traceId={}", traceId);
            safeComplete(emitter);
        } catch (DifyException exception) {
            if (closed.get()) {
                safeComplete(emitter);
                return;
            }
            log.warn("rag stream failed traceId={} datasetId={} upstreamStatus={} message={}",
                    traceId, datasetId, exception.upstreamStatus(), exception.getMessage());
            sendErrorAndComplete(emitter, traceId, ErrorCodes.DIFY_UNAVAILABLE,
                    exception.getMessage());
        } catch (Exception exception) {
            if (closed.get()) {
                safeComplete(emitter);
                return;
            }
            log.error("rag stream unexpected failure traceId={} datasetId={}", traceId, datasetId, exception);
            sendErrorAndComplete(emitter, traceId, ErrorCodes.DIFY_UNAVAILABLE,
                    "知识库问答服务暂不可用，请稍后重试");
        }
    }

    private String appKeyFor(String datasetId) {
        String datasetAId = blankToEmpty(datasetProperties.datasetAId());
        String datasetBId = blankToEmpty(datasetProperties.datasetBId());
        if (datasetId.equals(datasetAId)) {
            if (chatProperties.appAKey().isBlank()) {
                throw new DifyException("A 知识库问答应用未配置，请联系管理员", 0);
            }
            return chatProperties.appAKey();
        }
        if (datasetId.equals(datasetBId)) {
            if (chatProperties.appBKey().isBlank()) {
                throw new DifyException("B 知识库问答应用未配置，请联系管理员", 0);
            }
            return chatProperties.appBKey();
        }
        throw new DifyException("知识库问答服务未配置，请联系管理员", 0);
    }

    static boolean isRefusal(String answer) {
        StringBuilder compact = new StringBuilder(answer.length());
        for (int index = 0; index < answer.length(); index++) {
            char character = answer.charAt(index);
            if (!Character.isWhitespace(character)) {
                compact.append(character);
            }
        }
        String compactText = compact.toString();
        return REFUSAL_MARKERS.stream().anyMatch(compactText::contains);
    }

    private void sendDelta(SseEmitter emitter, String delta, AtomicBoolean closed) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        sendEvent(emitter, SseEmitter.event()
                .name("message")
                .data(Map.of("delta", delta)), closed);
    }

    private void sendEvent(SseEmitter emitter, SseEmitter.SseEventBuilder event, AtomicBoolean closed) {
        if (closed.get()) {
            throw new SseSendException("client disconnected", null);
        }
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException exception) {
            throw new SseSendException("failed to write SSE event", exception);
        }
    }

    private void sendErrorAndComplete(
            SseEmitter emitter,
            String traceId,
            int code,
            String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of(
                            "code", code,
                            "message", message,
                            "traceId", traceId)));
        } catch (IOException | IllegalStateException exception) {
            log.debug("failed to send SSE error traceId={}", traceId);
        }
        safeComplete(emitter);
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // 连接已关闭时无需再次处理。
        }
    }

    private static String firstNonBlank(String first, String second) {
        String normalizedFirst = blankToEmpty(first);
        return normalizedFirst.isEmpty() ? blankToEmpty(second) : normalizedFirst;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    private static final class SseSendException extends RuntimeException {

        private SseSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
