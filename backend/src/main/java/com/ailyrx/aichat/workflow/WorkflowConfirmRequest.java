package com.ailyrx.aichat.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 确认动作在服务层校验，非法值返回业务码 5005（而非参数 4000）。
 */
public record WorkflowConfirmRequest(
        @NotBlank(message = "确认动作不能为空")
        @Size(max = 20, message = "确认动作不合法")
        String action,
        @Size(max = 50000, message = "确认内容不能超过50000字")
        String content) {
}
