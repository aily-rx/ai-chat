package com.ailyrx.aichat.workflow;

import java.time.Instant;
import java.util.List;

/**
 * Dify human_input_required 事件中的必要字段。formToken 只在后端保存。
 * formContent 是 Dify 渲染后的确认页内容，暂停时用它补全未流式输出的草稿。
 */
public record DifyHumanInput(
        String formToken,
        String nodeId,
        String nodeTitle,
        String formContent,
        List<DifyFormAction> actions,
        Instant expiresAt) {
}
