package com.ailyrx.aichat.dify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dify Service API 的最小适配层，只暴露知识库文档上传与状态查询。
 */
@Component
public class DifyClient {

    private static final Logger log = LoggerFactory.getLogger(DifyClient.class);
    private static final int ERROR_BODY_LIMIT = 512;
    private static final byte[] CRLF = new byte[] {13, 10};
    private static final String DEFAULT_ERROR_MESSAGE = "知识库服务暂不可用，请稍后重试";

    private final DifyProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DifyClient(DifyProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(positive(properties.connectTimeoutSeconds(), 10)))
                .build();
    }

    public DifyUploadResult uploadDocument(
            String datasetId,
            String fileName,
            String contentType,
            byte[] content,
            String traceId) {
        ensureConfigured();
        if (datasetId == null || datasetId.isBlank()) {
            throw new DifyException("知识库数据集未配置，请联系管理员", 0);
        }

        String boundary = "----AiChat" + UUID.randomUUID().toString().replace("-", "");
        byte[] body;
        try {
            body = buildMultipartBody(boundary, fileName, contentType, content);
        } catch (JsonProcessingException exception) {
            log.warn("dify request serialization failed traceId={}", traceId);
            throw new DifyException("知识库请求构造失败，请稍后重试", 0, exception);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri("/datasets/" + datasetId + "/document/create-by-file"))
                .timeout(Duration.ofSeconds(positive(properties.requestTimeoutSeconds(), 60)))
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        JsonNode root = send(request, "upload", traceId);
        JsonNode document = root.path("document");
        String documentId = document.path("id").asText("");
        if (documentId.isBlank()) {
            log.warn("dify upload response missing document id traceId={}", traceId);
            throw new DifyException("知识库服务返回异常，请稍后重试", 200);
        }

        return new DifyUploadResult(
                documentId,
                document.path("name").asText(fileName),
                root.path("batch").asText(""),
                document.path("indexing_status").asText("waiting"));
    }

    public DifyDocumentStatus getDocumentStatus(String datasetId, String documentId, String traceId) {
        ensureConfigured();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri("/datasets/" + datasetId + "/documents/" + documentId))
                .timeout(Duration.ofSeconds(positive(properties.requestTimeoutSeconds(), 60)))
                .header("Authorization", "Bearer " + properties.apiKey())
                .GET()
                .build();

        JsonNode root = send(request, "document-status", traceId);
        JsonNode data = root.path("data");
        if (!data.isObject()) {
            data = root;
        }

        Integer segmentCount = data.hasNonNull("segment_count")
                ? data.get("segment_count").asInt()
                : null;
        String error = data.hasNonNull("error") ? data.get("error").asText() : null;
        return new DifyDocumentStatus(
                data.path("indexing_status").asText(""),
                segmentCount,
                error);
    }

    private void ensureConfigured() {
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()
                || properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new DifyException("知识库服务未配置，请联系管理员", 0);
        }
    }

    private byte[] buildMultipartBody(
            String boundary,
            String fileName,
            String contentType,
            byte[] content) throws JsonProcessingException {
        DifyProcessRule processRule = DifyProcessRule.custom(
                normalizeSeparator(properties.separator()),
                positive(properties.maxTokens(), 800),
                Math.max(0, properties.chunkOverlap()));
        String dataJson = objectMapper.writeValueAsString(
                DifyUploadRequest.text("Chinese", processRule));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "--" + boundary);
        write(out, CRLF);
        write(out, "Content-Disposition: form-data; name=\"data\"");
        write(out, CRLF);
        write(out, "Content-Type: application/json; charset=UTF-8");
        write(out, CRLF);
        write(out, CRLF);
        write(out, dataJson);
        write(out, CRLF);

        write(out, "--" + boundary);
        write(out, CRLF);
        write(out, "Content-Disposition: form-data; name=\"file\"; filename=\""
                + safeHeaderValue(fileName, "document") + "\"");
        write(out, CRLF);
        write(out, "Content-Type: " + safeHeaderValue(contentType, "application/octet-stream"));
        write(out, CRLF);
        write(out, CRLF);
        write(out, content);
        write(out, CRLF);
        write(out, "--" + boundary + "--");
        write(out, CRLF);
        return out.toByteArray();
    }

    private JsonNode send(HttpRequest request, String operation, String traceId) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DifyException("知识库调用被中断，请稍后重试", 0, exception);
        } catch (IOException exception) {
            log.warn("dify {} io error traceId={} type={}",
                    operation, traceId, exception.getClass().getSimpleName());
            throw new DifyException(DEFAULT_ERROR_MESSAGE, 0, exception);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            log.warn("dify {} http error traceId={} status={} body={}",
                    operation, traceId, status, truncate(response.body()));
            throw new DifyException(DEFAULT_ERROR_MESSAGE, status);
        }

        String body = response.body();
        if (body == null || body.isBlank()) {
            log.warn("dify {} empty response traceId={} status={}", operation, traceId, status);
            throw new DifyException("知识库服务返回异常，请稍后重试", status);
        }

        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            log.warn("dify {} parse error traceId={} status={}", operation, traceId, status);
            throw new DifyException("知识库服务返回异常，请稍后重试", status, exception);
        }
    }

    private URI uri(String path) {
        String base = properties.baseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private static String normalizeSeparator(String separator) {
        if (separator != null) {
            return separator;
        }
        char newline = (char) 10;
        return ("" + newline + newline);
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static String safeHeaderValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        StringBuilder cleaned = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == (char) 34 || ch == (char) 92 || Character.isISOControl(ch)) {
                cleaned.append((char) 95);
            } else {
                cleaned.append(ch);
            }
        }
        String result = cleaned.toString().trim();
        return result.isEmpty() ? fallback : result;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        String cleaned = body.replace((char) 13, (char) 32).replace((char) 10, (char) 32);
        return cleaned.length() <= ERROR_BODY_LIMIT
                ? cleaned
                : cleaned.substring(0, ERROR_BODY_LIMIT);
    }

    private static void write(ByteArrayOutputStream out, String value) {
        write(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(ByteArrayOutputStream out, byte[] value) {
        out.writeBytes(value);
    }
}
