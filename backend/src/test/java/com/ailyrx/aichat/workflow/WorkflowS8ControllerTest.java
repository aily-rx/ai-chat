package com.ailyrx.aichat.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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
class WorkflowS8ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkflowRunStore runStore;

    @MockBean
    private DifyWorkflowClient difyWorkflowClient;

    @Test
    void pausesConfirmsModifiesAndExportsMarkdownAndDocx() throws Exception {
        pauseRun("run-s8", "正文内容");

        String paused = complete(beginRun());
        org.assertj.core.api.Assertions.assertThat(paused)
                .contains("event:done")
                .contains("\"status\":\"WAITING_CONFIRM\"")
                .contains("\"runId\":\"run-s8\"");

        mockMvc.perform(get("/api/workflow/run-s8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("WAITING_CONFIRM"))
                .andExpect(jsonPath("$.data.draft").value("正文内容"))
                .andExpect(jsonPath("$.data.toolResults.format.passed").value(true))
                .andExpect(jsonPath("$.data.answer").value(
                        org.hamcrest.Matchers.containsString("草稿如下")));

        mockMvc.perform(post("/api/workflow/run-s8/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"bad\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5005));

        mockMvc.perform(post("/api/workflow/run-s8/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"modify\",\"content\":\"修改后的正文\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.confirmation.action").value("modify"));

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(difyWorkflowClient).submitHumanInput(
                anyString(), anyString(), contentCaptor.capture(), anyString());
        org.assertj.core.api.Assertions.assertThat(contentCaptor.getValue()).isEqualTo("修改后的正文");

        mockMvc.perform(get("/api/workflow/run-s8/export").param("format", "md"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".md")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("修改后的正文")));

        mockMvc.perform(get("/api/workflow/run-s8/export").param("format", "docx"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".docx")));
    }

    @Test
    void rejectPreventsExportAndRetainsHistory() throws Exception {
        pauseRun("run-reject", "待驳回正文");
        complete(beginRun());

        mockMvc.perform(post("/api/workflow/run-reject/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"reject\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.history[0].status").value("WAITING_CONFIRM"))
                .andExpect(jsonPath("$.data.history[1].status").value("REJECTED"));

        mockMvc.perform(get("/api/workflow/run-reject/export").param("format", "md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5006));
    }

    @Test
    void rejectsUnconfirmedAndExpiredRunsWithoutServerError() throws Exception {
        runStore.save(new WorkflowRunState(
                "run-unconfirmed",
                "trace-unconfirmed",
                new WorkflowDocumentMeta("未确认通知", "通知", "各部门：", "AI 公文助手", "2026-11-25"),
                "answer",
                "草稿",
                Map.of(),
                List.of(),
                WorkflowRunStatus.WAITING_CONFIRM,
                "form-token-unconfirmed",
                Instant.now().plusSeconds(3600)));
        runStore.save(new WorkflowRunState(
                "run-expired",
                "trace-expired",
                new WorkflowDocumentMeta("过期通知", "通知", "各部门：", "AI 公文助手", "2026-11-25"),
                "answer",
                "草稿",
                Map.of(),
                List.of(),
                WorkflowRunStatus.WAITING_CONFIRM,
                "form-token-expired",
                Instant.now().minusSeconds(60)));

        mockMvc.perform(get("/api/workflow/run-unconfirmed/export").param("format", "md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5006));

        mockMvc.perform(get("/api/workflow/run-missing/export").param("format", "md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5006));

        mockMvc.perform(post("/api/workflow/run-expired/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"approve\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5005));

        mockMvc.perform(get("/api/workflow/run-expired"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.errorCode").value("5005"));
    }

    private void pauseRun(String runId, String draft) {
        doAnswer(invocation -> {
            Consumer<WorkflowNodeEvent> onNode = invocation.getArgument(1);
            onNode.accept(new WorkflowNodeEvent("format_parse", "解析格式结果", "code", "succeeded",
                    Map.of("passed", true, "issue_text", "")));
            onNode.accept(new WorkflowNodeEvent("terms_parse", "解析术语结果", "code", "succeeded",
                    Map.of("has_hits", false, "hit_text", "")));
            return new DifyWorkflowResult(
                    runId,
                    "paused",
                    Map.of(),
                    false,
                    new DifyHumanInput(
                            "form-token-" + runId,
                            "human_confirm",
                            "人工确认",
                            "格式与术语检查均通过。\n\n草稿如下：\n" + draft,
                            List.of(new DifyFormAction("approve", "通过"),
                                    new DifyFormAction("modify", "修改"),
                                    new DifyFormAction("reject", "驳回")),
                            Instant.now().plusSeconds(3600)));
        }).when(difyWorkflowClient).run(
                anyMap(), any(), any(), any(BooleanSupplier.class), anyString());
    }

    private MvcResult beginRun() throws Exception {
        return mockMvc.perform(post("/api/workflow/run/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept("text/event-stream, application/json")
                        .content("{\"input\":\"起草一份安全生产检查通知\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private String complete(MvcResult result) throws Exception {
        return mockMvc.perform(asyncDispatch(result))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
