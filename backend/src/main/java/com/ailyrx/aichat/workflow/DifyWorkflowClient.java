package com.ailyrx.aichat.workflow;

import com.ailyrx.aichat.dify.DifyException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dify Workflow 流式客户端。节点事件与文本增量转交上层，不记录问题或草稿内容。
 */
@Component
public class DifyWorkflowClient {

    private static final Logger log = LoggerFactory.getLogger(DifyWorkflowClient.class);
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 120;
    private static final Set<String> SUCCESS_STATUSES = Set.of("succeeded", "partial-succeeded");

    private final WorkflowProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DifyWorkflowClient(WorkflowProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(positiveOrDefault(
                        properties.connectTimeoutSeconds(), DEFAULT_CONNECT_TIMEOUT_SECONDS)))
                .build();
    }

    public DifyWorkflowResult run(
            Map<String, String> inputs,
            Consumer<WorkflowNodeEvent> onNode,
            Consumer<String> onDelta,
            BooleanSupplier cancelled,
            String traceId) {
        if (properties.apiKey().isBlank() || properties.baseUrl().isBlank()) {
            throw new DifyException("工作流服务未配置，请联系管理员", 0);
        }

        String body;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("inputs", inputs);
            payload.put("response_mode", "streaming");
            payload.put("user", "ai-chat-workflow-" + traceId);
            body = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new DifyException("工作流服务暂不可用，请稍后重试", 0, exception);
        }

        HttpRequest request = HttpRequest.newBuilder(workflowUri())
                .timeout(Duration.ofSeconds(positiveOrDefault(
                        properties.requestTimeoutSeconds(), DEFAULT_REQUEST_TIMEOUT_SECONDS)))
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("X-Trace-Id", traceId == null ? "" : traceId)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeQuietly(response.body());
                log.warn("dify workflow http error traceId={} upstreamStatus={}",
                        traceId, response.statusCode());
                throw new DifyException("工作流服务暂不可用，请稍后重试", response.statusCode());
            }
            return readStream(response.body(), onNode, onDelta, cancelled, traceId);
        } catch (DifyException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DifyException("工作流请求已中断，请重试", 0, exception);
        } catch (IOException exception) {
            throw new DifyException("工作流服务暂不可用，请稍后重试", 0, exception);
        }
    }

    private DifyWorkflowResult readStream(
            InputStream input,
            Consumer<WorkflowNodeEvent> onNode,
            Consumer<String> onDelta,
            BooleanSupplier cancelled,
            String traceId) throws IOException {
        String runId = null;
        String status = null;
        Map<String, Object> outputs = Map.of();
        boolean finished = false;
        boolean streamedText = false;
        long startedAt = System.nanoTime();
        int nodeCount = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled.getAsBoolean()) {
                    break;
                }
                if (line.isBlank() || !line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).strip();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }

                JsonNode event = objectMapper.readTree(data);
                String eventName = event.path("event").asText("");
                JsonNode eventData = event.path("data");
                switch (eventName) {
                    case "workflow_started" -> {
                        runId = firstText(event, eventData, "workflow_run_id", "id");
                    }
                    case "node_started" -> {
                        nodeCount++;
                        onNode.accept(nodeEvent(eventData, "started"));
                    }
                    case "node_finished" -> onNode.accept(nodeEvent(
                            eventData, normalizeNodeStatus(textOrNull(eventData, "status"))));
                    case "text_chunk" -> {
                        String text = textOrNull(eventData, "text");
                        if (text != null && !text.isEmpty()) {
                            streamedText = true;
                            onDelta.accept(text);
                        }
                    }
                    case "message" -> {
                        String text = textOrNull(event, "answer");
                        if (text == null) {
                            text = textOrNull(eventData, "answer");
                        }
                        if (text != null && !text.isEmpty()) {
                            streamedText = true;
                            onDelta.accept(text);
                        }
                    }
                    case "workflow_finished" -> {
                        finished = true;
                        String finishedRunId = firstText(event, eventData, "workflow_run_id", "id");
                        if (finishedRunId != null) {
                            runId = finishedRunId;
                        }
                        status = textOrNull(eventData, "status");
                        JsonNode outputNode = eventData.path("outputs");
                        if (outputNode.isObject()) {
                            outputs = objectMapper.convertValue(outputNode, new TypeReference<>() { });
                        }
                    }
                    case "error" -> {
                        log.warn("dify workflow stream returned error traceId={} status={} code={}",
                                traceId,
                                event.path("status").asText(""),
                                event.path("code").asText(""));
                        throw new DifyException("工作流执行失败，请稍后重试", 0);
                    }
                    default -> {
                        // 未识别事件忽略，不向前端透传。
                    }
                }
            }
        } catch (JsonProcessingException exception) {
            throw new DifyException("工作流服务返回异常数据，请稍后重试", 0, exception);
        }

        if (cancelled.getAsBoolean()) {
            throw new DifyException("工作流请求已取消", 0);
        }
        if (!finished) {
            throw new DifyException("工作流未正常结束，请稍后重试", 0);
        }
        if (status == null || !SUCCESS_STATUSES.contains(status.toLowerCase())) {
            log.warn("dify workflow finished without success traceId={} status={}", traceId, status);
            throw new DifyException("工作流执行失败，请稍后重试", 0);
        }

        log.info("dify workflow finished traceId={} runId={} nodes={} streamedText={} elapsedMs={}",
                traceId, runId, nodeCount, streamedText, elapsedMillis(startedAt));
        return new DifyWorkflowResult(runId, status, outputs, streamedText);
    }

    private WorkflowNodeEvent nodeEvent(JsonNode data, String status) {
        String nodeId = firstText(data, data, "node_id", "id");
        String name = textOrNull(data, "title");
        String nodeType = textOrNull(data, "node_type");
        return new WorkflowNodeEvent(nodeId, name, nodeType, status);
    }

    private String normalizeNodeStatus(String status) {
        if (status == null) {
            return "unknown";
        }
        return switch (status.toLowerCase()) {
            case "succeeded" -> "succeeded";
            case "failed" -> "failed";
            case "stopped" -> "stopped";
            default -> status;
        };
    }

    private String firstText(JsonNode primary, JsonNode fallback, String... fields) {
        for (String field : fields) {
            String value = textOrNull(primary, field);
            if (value != null && !value.isEmpty()) {
                return value;
            }
            if (fallback != null) {
                value = textOrNull(fallback, field);
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    private URI workflowUri() {
        String baseUrl = properties.baseUrl();
        String normalized = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        return URI.create(normalized + "/workflows/run");
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // 关闭失败不影响已确定的错误响应。
        }
    }
}
