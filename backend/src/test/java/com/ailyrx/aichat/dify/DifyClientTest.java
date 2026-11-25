package com.ailyrx.aichat.dify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DifyClientTest {

    private HttpServer server;
    private DifyClient client;
    private AtomicReference<String> requestBody;

    @BeforeEach
    void setUp() {
        requestBody = new AtomicReference<>("");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void uploadDocumentParsesResponseAndSendsMultipart() throws Exception {
        startServer("/v1/datasets/ds-b/document/create-by-file", 200,
                "{\"document\":{\"id\":\"doc-1\",\"name\":\"notice.txt\",\"indexing_status\":\"waiting\"},\"batch\":\"batch-1\"}");

        DifyUploadResult result = client.uploadDocument(
                "ds-b", "notice.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8), "trace");

        assertEquals("doc-1", result.documentId());
        assertEquals("notice.txt", result.documentName());
        assertEquals("batch-1", result.batchId());
        assertEquals("waiting", result.indexingStatus());
        String body = requestBody.get();
        assertTrue(body.contains("name=\"data\""));
        assertTrue(body.contains("name=\"file\"; filename=\"notice.txt\""));
        assertTrue(body.contains("\"indexing_technique\":\"high_quality\""));
        assertTrue(body.contains("\"max_tokens\":800"));
        assertTrue(body.contains("\"chunk_overlap\":100"));
        assertTrue(body.contains("hello"));
    }

    @Test
    void getDocumentStatusParsesDetail() throws Exception {
        startServer("/v1/datasets/ds-b/documents/doc-1", 200,
                "{\"id\":\"doc-1\",\"indexing_status\":\"completed\",\"segment_count\":3,\"error\":null}");

        DifyDocumentStatus status = client.getDocumentStatus("ds-b", "doc-1", "trace");

        assertEquals("completed", status.indexingStatus());
        assertEquals(3, status.segmentCount());
        assertEquals(null, status.error());
    }

    @Test
    void uploadMapsHttpErrorWithoutLeakingUpstreamBody() throws Exception {
        startServer("/v1/datasets/ds-b/document/create-by-file", 503,
                "{\"message\":\"upstream unavailable\"}");

        DifyException exception = assertThrows(DifyException.class, () -> client.uploadDocument(
                "ds-b", "notice.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8), "trace"));

        assertEquals(503, exception.upstreamStatus());
        assertFalse(exception.getMessage().contains("upstream unavailable"));
    }

    @Test
    void rejectsMissingConfigurationBeforeNetwork() {
        DifyClient unconfigured = new DifyClient(
                new DifyProperties("http://127.0.0.1:1/v1", "", "ds-b", "ds-a", "ds-b",
                        1000, 60000, 1, 5, "\n\n", 800, 100),
                new ObjectMapper());

        DifyException exception = assertThrows(DifyException.class, () -> unconfigured.uploadDocument(
                "ds-b", "notice.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8), "trace"));

        assertEquals("知识库服务未配置，请联系管理员", exception.getMessage());
    }

    private void startServer(String path, int status, String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        client = new DifyClient(
                new DifyProperties(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                        "test-key", "ds-b", "ds-a", "ds-b",
                        1000, 60000, 2, 5, "\n\n", 800, 100),
                new ObjectMapper());
    }
}
