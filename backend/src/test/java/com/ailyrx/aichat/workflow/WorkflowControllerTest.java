package com.ailyrx.aichat.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ailyrx.aichat.dify.DifyException;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "app.workflow.base-url=http://127.0.0.1:1/v1",
        "app.workflow.api-key=test-workflow-key"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class WorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DifyWorkflowClient difyWorkflowClient;

    @Test
    void streamsNodeMessageAndDoneWithoutSecrets() throws Exception {
        doAnswer(invocation -> {
            Consumer<WorkflowNodeEvent> onNode = invocation.getArgument(1);
            Consumer<String> onDelta = invocation.getArgument(2);
            onNode.accept(new WorkflowNodeEvent("start", "开始", "start", "started"));
            onDelta.accept("工作流草稿");
            onNode.accept(new WorkflowNodeEvent("start", "开始", "start", "succeeded"));
            return new DifyWorkflowResult("run-1", "succeeded", Map.of("answer", "最终答案"), true);
        }).when(difyWorkflowClient).run(anyMap(), any(), any(), any(BooleanSupplier.class), anyString());

        MvcResult result = begin("{\"input\":\"起草一份安全生产检查通知\"}");
        String body = responseBody(complete(result));

        assertThat(body)
                .contains("event:node")
                .contains("\"nodeId\":\"start\"")
                .contains("\"status\":\"started\"")
                .contains("event:message")
                .contains("\"delta\":\"工作流草稿\"")
                .contains("event:done")
                .contains("\"runId\":\"run-1\"")
                .contains("\"traceId\":")
                .doesNotContain("event:error")
                .doesNotContain("test-workflow-key");
        assertThat(body.indexOf("event:node")).isLessThan(body.indexOf("event:done"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> inputsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(difyWorkflowClient).run(
                inputsCaptor.capture(), any(), any(), any(BooleanSupplier.class), anyString());
        assertThat(inputsCaptor.getValue())
                .containsEntry("task", "起草一份安全生产检查通知")
                .containsEntry("doc_type", "通知")
                .containsKey("date");
    }

    @Test
    void sendsFinalOutputWhenWorkflowDidNotStreamText() throws Exception {
        doAnswer(invocation -> new DifyWorkflowResult(
                "run-2", "succeeded", Map.of("answer", "兜底最终稿"), false))
                .when(difyWorkflowClient).run(anyMap(), any(), any(), any(BooleanSupplier.class), anyString());

        MvcResult result = begin("{\"input\":\"起草通知\"}");
        String body = responseBody(complete(result));

        assertThat(body)
                .contains("event:message")
                .contains("\"delta\":\"兜底最终稿\"")
                .contains("event:done");
    }

    @Test
    void upstreamFailureSends5004ErrorWithoutDone() throws Exception {
        doThrow(new DifyException("工作流执行失败，请稍后重试", 503))
                .when(difyWorkflowClient).run(anyMap(), any(), any(), any(BooleanSupplier.class), anyString());

        MvcResult result = begin("{\"input\":\"起草通知\"}");
        String body = responseBody(complete(result));

        assertThat(body)
                .contains("event:error")
                .contains("\"code\":5004")
                .contains("工作流执行失败")
                .doesNotContain("event:done")
                .doesNotContain("test-workflow-key");
    }

    @Test
    void rejectsBlankInputBeforeSseStarts() throws Exception {
        mockMvc.perform(post("/api/workflow/run/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.message").value("任务描述不能为空"));
    }

    private MvcResult begin(String json) throws Exception {
        return mockMvc.perform(post("/api/workflow/run/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept("text/event-stream, application/json")
                        .content(json))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private MvcResult complete(MvcResult result) throws Exception {
        return mockMvc.perform(asyncDispatch(result)).andReturn();
    }

    private String responseBody(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
