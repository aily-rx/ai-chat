package com.ailyrx.aichat.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record WorkflowRunRequest(
        @NotBlank(message = "任务描述不能为空")
        @Size(max = 2000, message = "任务描述不能超过2000字")
        String input,
        @Size(max = 20000, message = "草稿不能超过20000字")
        String draft,
        @Size(max = 20, message = "文档列表不能超过20项")
        List<String> documentIds) {

    public List<String> normalizedDocumentIds() {
        return documentIds == null ? List.of() : List.copyOf(documentIds);
    }
}
