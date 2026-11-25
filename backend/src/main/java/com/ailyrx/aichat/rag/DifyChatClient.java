package com.ailyrx.aichat.rag;

import com.ailyrx.aichat.dify.DifyException;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dify Chatflow 流式客户端。只透传最终回答片段，忽略 reasoning_chunk 等内部事件。
 */
@Component
public class DifyChatClient {

    private static final Logger log = LoggerFactory.getLogger(DifyChatClient.class);
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 120;

    private final DifyChatProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DifyChatClient(DifyChatProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(positiveOrDefault(
                        properties.connectTimeoutSeconds(), DEFAULT_CONNECT_TIMEOUT_SECONDS)))
                .build();
    }

    public DifyChatResult streamChat(
            String apiKey,
            String question,
            Consumer<String> onDelta,
            BooleanSupplier cancelled,
            String traceId) {
        if (apiKey == null || apiKey.isBlank() || properties.baseUrl().isBlank()) {
            throw new DifyException("知识库问答服务未配置，请联系管理员", 0);
        }

        String body;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("inputs", Map.of());
            payload.put("query", question);
            payload.put("response_mode", "streaming");
            payload.put("conversation_id", "");
            payload.put("user", "ai-chat-" + traceId);
            payload.put("auto_generate_name", false);
            body = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new DifyException("知识库问答服务暂不可用，请稍后重试", 0, exception);
        }

        HttpRequest request = HttpRequest.newBuilder(chatUri())
                .timeout(Duration.ofSeconds(positiveOrDefault(
                        properties.requestTimeoutSeconds(), DEFAULT_REQUEST_TIMEOUT_SECONDS)))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeQuietly(response.body());
                log.warn("dify chat http error traceId={} upstreamStatus={}", traceId, response.statusCode());
                throw new DifyException("知识库问答服务暂不可用，请稍后重试", response.statusCode());
            }
            return readStream(response.body(), onDelta, cancelled, traceId);
        } catch (DifyException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DifyException("知识库问答请求已中断，请重试", 0, exception);
        } catch (IOException exception) {
            throw new DifyException("知识库问答服务暂不可用，请稍后重试", 0, exception);
        }
    }

    private DifyChatResult readStream(
            InputStream input,
            Consumer<String> onDelta,
            BooleanSupplier cancelled,
            String traceId) throws IOException {
        String messageId = null;
        List<DifyRetrieverResource> resources = List.of();
        int deltaCount = 0;
        long startedAt = System.nanoTime();

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
                switch (eventName) {
                    case "message" -> {
                        String answer = event.path("answer").asText("");
                        if (!answer.isEmpty()) {
                            deltaCount++;
                            onDelta.accept(answer);
                        }
                    }
                    case "message_end" -> {
                        messageId = textOrNull(event, "message_id");
                        resources = parseResources(event.path("metadata").path("retriever_resources"));
                    }
                    case "error" -> {
                        log.warn("dify chat stream returned error traceId={} status={} code={}",
                                traceId,
                                event.path("status").asText(""),
                                event.path("code").asText(""));
                        throw new DifyException("知识库问答服务返回错误，请稍后重试", 0);
                    }
                    default -> {
                        // reasoning_chunk、workflow/node 事件不向前端透传。
                    }
                }
            }
        } catch (JsonProcessingException exception) {
            throw new DifyException("知识库问答服务返回异常数据，请稍后重试", 0, exception);
        }

        log.info("dify chat stream finished traceId={} messageId={} deltas={} citations={} elapsedMs={}",
                traceId, messageId, deltaCount, resources.size(), elapsedMillis(startedAt));
        return new DifyChatResult(messageId, resources);
    }

    private List<DifyRetrieverResource> parseResources(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        List<DifyRetrieverResource> resources = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                continue;
            }
            resources.add(new DifyRetrieverResource(
                    textOrNull(item, "dataset_id"),
                    textOrNull(item, "document_id"),
                    textOrNull(item, "document_name"),
                    intOrNull(item, "segment_position"),
                    textOrNull(item, "content"),
                    doubleOrNull(item, "score")));
        }
        return resources;
    }

    private URI chatUri() {
        String baseUrl = properties.baseUrl();
        String normalized = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        return URI.create(normalized + "/chat-messages");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.canConvertToInt()) {
            return value.asInt();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText().strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isTextual()) {
            try {
                return Double.parseDouble(value.asText().strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
            // 响应已失败，关闭异常无额外处理价值。
        }
    }
}
