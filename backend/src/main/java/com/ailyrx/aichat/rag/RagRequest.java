package com.ailyrx.aichat.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagRequest(
        @NotBlank(message = "question 不能为空")
        @Size(max = 1000, message = "question 不能超过 1000 字符")
        String question,

        String datasetId) {

    public String normalizedQuestion() {
        return question == null ? "" : question.strip();
    }
}
