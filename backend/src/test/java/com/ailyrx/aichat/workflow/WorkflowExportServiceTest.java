package com.ailyrx.aichat.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowExportServiceTest {

    private final WorkflowExportService service = new WorkflowExportService();

    @Test
    void notesWhenRetrievalDidNotRun() {
        WorkflowRunState state = succeededState(List.of());

        String markdown = service.markdown(state);

        assertThat(markdown)
                .contains("## 引用列表")
                .contains("本次运行未执行检索")
                .contains("## 确认记录")
                .contains("动作：通过");
    }

    @Test
    void notesWhenRetrievalReturnedNoCitations() {
        WorkflowRunState state = succeededState(List.of(
                new WorkflowNodeSnapshot("retrieval", "知识检索", "knowledge-retrieval", "succeeded")));

        String markdown = service.markdown(state);

        assertThat(markdown).contains("本次检索未返回可引用片段");
    }

    private WorkflowRunState succeededState(List<WorkflowNodeSnapshot> nodes) {
        WorkflowRunState state = new WorkflowRunState(
                "run-export",
                "trace-export",
                new WorkflowDocumentMeta("导出测试通知", "通知", "各部门：", "AI 公文助手", "2026-11-26"),
                "answer",
                "正文",
                Map.of(),
                nodes,
                WorkflowRunStatus.WAITING_CONFIRM,
                "form-token",
                Instant.now().plusSeconds(3600));
        state.confirm(WorkflowRunStatus.SUCCEEDED,
                new WorkflowConfirmation("approve", "正文", "trace-confirm", Instant.now()), "approve");
        return state;
    }
}
