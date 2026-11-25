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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
            payload.put("user", difyUser(traceId));
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

    /**
     * 提交人工确认表单。Dify 接受后会自动恢复暂停的 Workflow。
     */
    public void submitHumanInput(String formToken, String action, String content, String traceId) {
        if (properties.apiKey().isBlank() || properties.baseUrl().isBlank()) {
            throw new DifyException("工作流服务未配置，请联系管理员", 0);
        }
        if (formToken == null || !formToken.matches("[A-Za-z0-9_-]{1,200}")) {
            throw new DifyException("确认信息已失效，请重新发起任务", 0);
        }

        String body;
        try {
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("content", content == null ? "" : content);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("inputs", inputs);
            payload.put("action", action);
            payload.put("user", difyUser(traceId));
            body = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new DifyException("确认提交失败，请稍后重试", 0, exception);
        }

        HttpRequest request = HttpRequest.newBuilder(humanInputUri(formToken))
                .timeout(Duration.ofSeconds(positiveOrDefault(
                        properties.requestTimeoutSeconds(), DEFAULT_REQUEST_TIMEOUT_SECONDS)))
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .header("X-Trace-Id", traceId == null ? "" : traceId)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            closeQuietly(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("dify human input submit failed traceId={} upstreamStatus={}",
                        traceId, response.statusCode());
                throw new DifyException("确认已失效或已提交，请刷新后重试", response.statusCode());
            }
            log.info("dify human input submitted traceId={} action={}", traceId, action);
        } catch (DifyException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DifyException("确认提交已中断，请刷新后重试", 0, exception);
        } catch (IOException exception) {
            throw new DifyException("确认提交失败，请稍后重试", 0, exception);
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
        DifyHumanInput humanInput = null;
        boolean finished = false;
        boolean paused = false;
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
                            eventData,
                            normalizeNodeStatus(textOrNull(eventData, "status")),
                            true));
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
                    case "human_input_required" -> {
                        humanInput = humanInput(event, eventData);
                        String inputRunId = firstText(event, eventData, "workflow_run_id", "id");
                        if (inputRunId != null) {
                            runId = inputRunId;
                        }
                    }
                    case "workflow_paused" -> {
                        paused = true;
                        String pausedRunId = firstText(event, eventData, "workflow_run_id", "id");
                        if (pausedRunId != null) {
                            runId = pausedRunId;
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
        if (paused || humanInput != null) {
            if (humanInput == null) {
                throw new DifyException("工作流未返回确认信息，请稍后重试", 0);
            }
            log.info("dify workflow paused traceId={} runId={} node={} nodes={} elapsedMs={}",
                    traceId, runId, humanInput.nodeId(), nodeCount, elapsedMillis(startedAt));
            return new DifyWorkflowResult(runId, "paused", outputs, streamedText, humanInput);
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
        return nodeEvent(data, status, false);
    }

    private WorkflowNodeEvent nodeEvent(JsonNode data, String status, boolean includeOutputs) {
        String nodeId = firstText(data, data, "node_id", "id");
        String name = textOrNull(data, "title");
        String nodeType = textOrNull(data, "node_type");
        Map<String, Object> outputs = Map.of();
        JsonNode outputNode = data.path("outputs");
        if (includeOutputs && outputNode.isObject()) {
            Map<String, Object> parsed = objectMapper.convertValue(outputNode, new TypeReference<>() { });
            outputs = Collections.unmodifiableMap(new LinkedHashMap<>(parsed));
        }
        return new WorkflowNodeEvent(nodeId, name, nodeType, status, outputs);
    }

    private DifyHumanInput humanInput(JsonNode event, JsonNode data) {
        String formToken = firstText(data, event, "form_token");
        String nodeId = firstText(data, event, "node_id");
        String nodeTitle = firstText(data, event, "node_title");
        String formContent = firstText(data, event, "form_content");
        if (formContent == null || formContent.isBlank()) {
            formContent = textOrNull(data.path("resolved_default_values"), "content");
        }
        List<DifyFormAction> actions = new ArrayList<>();
        JsonNode actionNode = data.path("actions");
        if (actionNode.isArray()) {
            for (JsonNode item : actionNode) {
                String id = textOrNull(item, "id");
                String title = textOrNull(item, "title");
                if (id != null && !id.isBlank()) {
                    actions.add(new DifyFormAction(id, title == null ? id : title));
                }
            }
        }
        long expiration = data.path("expiration_time").asLong(0L);
        Instant expiresAt = expiration > 0
                ? Instant.ofEpochSecond(expiration)
                : Instant.now().plus(Duration.ofHours(1));
        return new DifyHumanInput(
                formToken, nodeId, nodeTitle, formContent, List.copyOf(actions), expiresAt);
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
        return URI.create(normalizedBaseUrl() + "/workflows/run");
    }

    private URI humanInputUri(String formToken) {
        return URI.create(normalizedBaseUrl() + "/form/human_input/" + formToken);
    }

    private String normalizedBaseUrl() {
        String baseUrl = properties.baseUrl();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String difyUser(String traceId) {
        return "ai-chat-workflow-" + (traceId == null ? "" : traceId);
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
