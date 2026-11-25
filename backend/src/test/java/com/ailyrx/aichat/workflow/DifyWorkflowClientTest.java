package com.ailyrx.aichat.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ailyrx.aichat.dify.DifyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DifyWorkflowClientTest {

    private HttpServer server;
    private AtomicReference<String> requestBody;
    private AtomicReference<String> authorization;
    private AtomicReference<String> traceHeader;

    @BeforeEach
    void setUp() {
        requestBody = new AtomicReference<>("");
        authorization = new AtomicReference<>("");
        traceHeader = new AtomicReference<>("");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void streamsNodeEventsTextAndWorkflowResult() throws Exception {
        String sse = String.join("\n",
                "data: {\"event\":\"workflow_started\",\"workflow_run_id\":\"run-1\",\"data\":{\"id\":\"run-1\"}}",
                "",
                "data: {\"event\":\"node_started\",\"data\":{\"node_id\":\"start\",\"title\":\"开始\",\"node_type\":\"start\"}}",
                "",
                "data: {\"event\":\"text_chunk\",\"data\":{\"text\":\"草稿\"}}",
                "",
                "data: {\"event\":\"node_finished\",\"data\":{\"node_id\":\"start\",\"title\":\"开始\",\"node_type\":\"start\",\"status\":\"succeeded\"}}",
                "",
                "data: {\"event\":\"workflow_finished\",\"workflow_run_id\":\"run-1\",\"data\":{\"status\":\"succeeded\",\"outputs\":{\"answer\":\"最终答案\"}}}",
                "");
        startServer(200, sse);
        DifyWorkflowClient client = client("test-workflow-key");

        List<WorkflowNodeEvent> nodes = new ArrayList<>();
        List<String> deltas = new ArrayList<>();
        DifyWorkflowResult result = client.run(
                Map.of("task", "起草通知"), nodes::add, deltas::add, () -> false, "trace123");

        assertThat(nodes).extracting(WorkflowNodeEvent::status).containsExactly("started", "succeeded");
        assertThat(nodes.get(0).nodeId()).isEqualTo("start");
        assertThat(nodes.get(0).nodeType()).isEqualTo("start");
        assertThat(deltas).containsExactly("草稿");
        assertThat(result.runId()).isEqualTo("run-1");
        assertThat(result.status()).isEqualTo("succeeded");
        assertThat(result.outputs()).containsEntry("answer", "最终答案");
        assertThat(result.streamedText()).isTrue();
        assertThat(authorization.get()).isEqualTo("Bearer test-workflow-key");
        assertThat(traceHeader.get()).isEqualTo("trace123");
        assertThat(requestBody.get())
                .contains("\"task\":\"起草通知\"")
                .contains("\"response_mode\":\"streaming\"")
                .contains("\"user\":\"ai-chat-workflow-trace123\"");
    }

    @Test
    void acceptsPartialSuccessWhenToolFallbackHandlesFailure() throws Exception {
        String sse = String.join("\n",
                "data: {\"event\":\"text_chunk\",\"data\":{\"text\":\"工具不可用兜底\"}}",
                "",
                "data: {\"event\":\"workflow_finished\",\"workflow_run_id\":\"run-2\","
                        + "\"data\":{\"status\":\"partial-succeeded\",\"outputs\":{\"answer\":\"工具不可用兜底\"}}}",
                "");
        startServer(200, sse);

        List<String> deltas = new ArrayList<>();
        DifyWorkflowResult result = client("test-key").run(
                Map.of("task", "问题"), event -> { }, deltas::add, () -> false, "trace");

        assertThat(result.status()).isEqualTo("partial-succeeded");
        assertThat(result.outputs()).containsEntry("answer", "工具不可用兜底");
        assertThat(deltas).containsExactly("工具不可用兜底");
    }

    @Test
    void mapsUpstreamErrorEventWithoutLeakingDetails() throws Exception {
        startServer(200, "data: {\"event\":\"error\",\"message\":\"upstream secret\"}\n");

        assertThatThrownBy(() -> client("test-key").run(
                Map.of("task", "问题"), event -> { }, delta -> { }, () -> false, "trace"))
                .isInstanceOf(DifyException.class)
                .hasMessage("工作流执行失败，请稍后重试")
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain("secret"));
    }

    @Test
    void mapsHttpErrorWithoutLeakingUpstreamBody() throws Exception {
        startServer(503, "{\"message\":\"upstream secret\"}");

        assertThatThrownBy(() -> client("test-key").run(
                Map.of("task", "问题"), event -> { }, delta -> { }, () -> false, "trace"))
                .isInstanceOf(DifyException.class)
                .hasMessage("工作流服务暂不可用，请稍后重试")
                .satisfies(exception -> assertThat(((DifyException) exception).upstreamStatus()).isEqualTo(503));
    }

    @Test
    void rejectsMissingWorkflowKeyBeforeNetwork() {
        DifyWorkflowClient unconfigured = new DifyWorkflowClient(
                new WorkflowProperties("http://127.0.0.1:1/v1", "", 1, 1),
                new ObjectMapper());

        assertThatThrownBy(() -> unconfigured.run(
                Map.of("task", "问题"), event -> { }, delta -> { }, () -> false, "trace"))
                .isInstanceOf(DifyException.class)
                .hasMessage("工作流服务未配置，请联系管理员");
    }

    @Test
    void rejectsStreamWithoutWorkflowFinished() throws Exception {
        startServer(200, "data: {\"event\":\"workflow_started\",\"workflow_run_id\":\"run-1\"}\n");

        assertThatThrownBy(() -> client("test-key").run(
                Map.of("task", "问题"), event -> { }, delta -> { }, () -> false, "trace"))
                .isInstanceOf(DifyException.class)
                .hasMessage("工作流未正常结束，请稍后重试");
    }

    private DifyWorkflowClient client(String key) {
        int port = server.getAddress().getPort();
        return new DifyWorkflowClient(
                new WorkflowProperties("http://127.0.0.1:" + port + "/v1", key, 1, 5),
                new ObjectMapper());
    }

    private void startServer(int status, String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/workflows/run", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            traceHeader.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }
}
