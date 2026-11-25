package com.ailyrx.aichat.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ailyrx.aichat.dify.DifyException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "app.dify.dataset-a-id=dataset-a",
        "app.dify.dataset-b-id=dataset-b",
        "app.dify.default-dataset-id=dataset-b",
        "app.dify-chat.base-url=http://127.0.0.1:1/v1",
        "app.dify-chat.app-a-key=test-app-key-a",
        "app.dify-chat.app-b-key=test-app-key-b"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class RagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DifyChatClient difyChatClient;

    @Test
    void streamsMessagesThenCitationsThenDone() throws Exception {
        stubChat(
                List.of("根据资料，", "2027年1月1日放假。"),
                List.of(new DifyRetrieverResource(
                        "dataset-b", "doc-1", "国务院办公厅关于2027年部分节假日安排的通知.pdf",
                        1, "元旦：1月1日放假，共1天。", 0.83)));

        MvcResult result = begin("{\"question\":\"2027年元旦怎么放假？\"}");
        MvcResult dispatched = complete(result);

        String body = responseBody(dispatched);
        assertThat(body)
                .contains("event:message")
                .contains("\"delta\":\"根据资料，\"")
                .contains("\"delta\":\"2027年1月1日放假。\"")
                .contains("event:citations")
                .contains("\"docId\":\"doc-1\"")
                .contains("\"docName\":\"国务院办公厅关于2027年部分节假日安排的通知.pdf\"")
                .contains("\"segmentIndex\":1")
                .contains("\"excerpt\":\"元旦：1月1日放假，共1天。\"")
                .contains("\"page\":null")
                .contains("\"score\":0.83")
                .contains("event:done")
                .contains("\"runId\":\"msg-123\"")
                .contains("\"elapsedMs\":")
                .doesNotContain("event:error")
                .doesNotContain("test-app-key-b");

        assertThat(body.indexOf("event:message")).isLessThan(body.indexOf("event:citations"));
        assertThat(body.indexOf("event:citations")).isLessThan(body.indexOf("event:done"));
        assertThat(dispatched.getResponse().getHeader("X-Trace-Id")).isNotBlank();
        verify(difyChatClient).streamChat(
                eq("test-app-key-b"), eq("2027年元旦怎么放假？"), any(), any(), anyString());
    }

    @Test
    void routesDatasetAAliasToApplicationA() throws Exception {
        stubChat(List.of("A库回答"), List.of(new DifyRetrieverResource(
                "dataset-a", "doc-a", "A资料.pdf", 2, "A资料片段", 0.7)));

        MvcResult result = begin("{\"question\":\"A库问题\",\"datasetId\":\"A\"}");
        String body = responseBody(complete(result));

        assertThat(body).contains("event:done");
        verify(difyChatClient).streamChat(
                eq("test-app-key-a"), eq("A库问题"), any(), any(), anyString());
    }

    @Test
    void acceptsConfiguredDatasetIdOutsideAliases() throws Exception {
        stubChat(List.of("A库回答"), List.of(new DifyRetrieverResource(
                "dataset-a", "doc-a", "A资料.pdf", 2, "A资料片段", 0.7)));

        MvcResult result = begin("{\"question\":\"A库问题\",\"datasetId\":\"dataset-a\"}");
        assertThat(responseBody(complete(result))).contains("event:done");
        verify(difyChatClient).streamChat(
                eq("test-app-key-a"), eq("A库问题"), any(), any(), anyString());
    }

    @Test
    void refusalSendsEmptyCitationsAndDoneInsteadOfError() throws Exception {
        stubChat(
                List.of("资料不足，无法回答该问题。"),
                List.of(new DifyRetrieverResource(
                        "dataset-b", "doc-1", "不应出现的引用.pdf", 1, "不应出现的内容", 0.9)));

        MvcResult result = begin("{\"question\":\"资料中没有的问题\"}");
        String body = responseBody(complete(result));

        assertThat(body)
                .contains("event:citations")
                .contains("\"items\":[]")
                .contains("event:done")
                .doesNotContain("event:error")
                .doesNotContain("不应出现的引用.pdf");
    }

    @Test
    void nonRefusalWithoutCitationsSendsErrorAndNoDone() throws Exception {
        stubChat(List.of("这是一段没有引用的回答。"), List.of());

        MvcResult result = begin("{\"question\":\"没有引用的回答\"}");
        String body = responseBody(complete(result));

        assertThat(body)
                .contains("event:error")
                .contains("\"code\":5002")
                .contains("本次引用不可用")
                .doesNotContain("event:citations")
                .doesNotContain("event:done");
    }

    @Test
    void upstreamFailureSendsErrorWithoutDoneOrSecret() throws Exception {
        doThrow(new DifyException("知识库问答服务暂不可用，请稍后重试", 503))
                .when(difyChatClient).streamChat(anyString(), anyString(), any(), any(), anyString());

        MvcResult result = begin("{\"question\":\"上游异常\"}");
        String body = responseBody(complete(result));

        assertThat(body)
                .contains("event:error")
                .contains("\"code\":5002")
                .contains("知识库问答服务暂不可用")
                .doesNotContain("event:done")
                .doesNotContain("test-app-key-b");
    }

    @Test
    void rejectsDatasetIdOutsideWhitelist() throws Exception {
        mockMvc.perform(post("/api/rag/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"越权问题\",\"datasetId\":\"other-dataset\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.message").value("datasetId 只支持 A/B 或对应数据集 ID"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void rejectsBlankQuestion() throws Exception {
        mockMvc.perform(post("/api/rag/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.message").value("question 不能为空"));
    }

    @Test
    void rejectsQuestionOverOneThousandCharacters() throws Exception {
        mockMvc.perform(post("/api/rag/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + "问".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.message").value("question 不能超过 1000 字符"));
    }

    private void stubChat(List<String> deltas, List<DifyRetrieverResource> resources) {
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(2);
            deltas.forEach(onDelta);
            return new DifyChatResult("msg-123", resources);
        }).when(difyChatClient).streamChat(anyString(), anyString(), any(), any(), anyString());
    }

    private MvcResult begin(String requestBody) throws Exception {
        return mockMvc.perform(post("/api/rag/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private MvcResult complete(MvcResult result) throws Exception {
        return mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(header().string("Cache-Control", "no-cache, no-transform"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();
    }

    private String responseBody(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
