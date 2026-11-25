package com.ailyrx.aichat.rag;

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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DifyChatClientTest {

    private HttpServer server;
    private DifyChatClient client;
    private AtomicReference<String> requestBody;
    private AtomicReference<String> authorization;

    @BeforeEach
    void setUp() {
        requestBody = new AtomicReference<>("");
        authorization = new AtomicReference<>("");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void forwardsOnlyMessageDeltasAndCollectsCitationsFromMessageEnd() throws Exception {
        String sse = String.join("\n",
                "data: {\"event\":\"workflow_started\",\"data\":{}}",
                "",
                "data: {\"event\":\"reasoning_chunk\",\"reasoning_content\":\"不应透传\"}",
                "",
                "data: {\"event\":\"message\",\"answer\":\"根据资料，\"}",
                "",
                "data: {\"event\":\"message\",\"answer\":\"2027年1月1日放假。\"}",
                "",
                "data: {\"event\":\"message_end\",\"message_id\":\"msg-1\",\"metadata\":{"
                        + "\"retriever_resources\":[{\"dataset_id\":\"ds-b\",\"document_id\":\"doc-1\","
                        + "\"document_name\":\"通知.pdf\",\"segment_position\":1,"
                        + "\"content\":\"元旦：1月1日放假\",\"score\":0.78}]}}",
                "");
        startServer(200, sse);

        List<String> deltas = new ArrayList<>();
        DifyChatResult result = client.streamChat(
                "test-key", "2027年元旦怎么放假？", deltas::add, () -> false, "trace123");

        assertThat(deltas).containsExactly("根据资料，", "2027年1月1日放假。");
        assertThat(result.messageId()).isEqualTo("msg-1");
        assertThat(result.resources()).hasSize(1);
        assertThat(result.resources().get(0).documentName()).isEqualTo("通知.pdf");
        assertThat(result.resources().get(0).segmentPosition()).isEqualTo(1);
        assertThat(result.resources().get(0).score()).isEqualTo(0.78);
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(requestBody.get())
                .contains("\"query\":\"2027年元旦怎么放假？\"")
                .contains("\"response_mode\":\"streaming\"")
                .contains("\"conversation_id\":\"\"")
                .contains("\"user\":\"ai-chat-trace123\"")
                .doesNotContain("不应透传");
    }

    @Test
    void mapsHttpErrorWithoutLeakingUpstreamBody() throws Exception {
        startServer(503, "{\"message\":\"upstream secret\"}");

        assertThatThrownBy(() -> client.streamChat(
                "test-key", "问题", delta -> { }, () -> false, "trace"))
                .isInstanceOf(DifyException.class)
                .hasMessage("知识库问答服务暂不可用，请稍后重试")
                .satisfies(exception -> assertThat(((DifyException) exception).upstreamStatus()).isEqualTo(503));
    }

    @Test
    void mapsUpstreamErrorEventWithoutLeakingDetails() throws Exception {
        startServer(200, "data: {\"event\":\"error\",\"message\":\"upstream secret\"}\n");

        assertThatThrownBy(() -> client.streamChat(
                "test-key", "问题", delta -> { }, () -> false, "trace"))
                .isInstanceOf(DifyException.class)
                .hasMessage("知识库问答服务返回错误，请稍后重试");
    }

    @Test
    void rejectsMissingApplicationKeyBeforeNetwork() {
        DifyChatClient unconfigured = new DifyChatClient(
                new DifyChatProperties("http://127.0.0.1:1/v1", "", "", 1, 1),
                new ObjectMapper());

        assertThatThrownBy(() -> unconfigured.streamChat(
                "", "问题", delta -> { }, () -> false, "trace"))
                .isInstanceOf(DifyException.class)
                .hasMessage("知识库问答服务未配置，请联系管理员");
    }

    private void startServer(int status, String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat-messages", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        client = new DifyChatClient(
                new DifyChatProperties(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                        "test-key-a", "test-key-b", 2, 10),
                new ObjectMapper());
    }
}
