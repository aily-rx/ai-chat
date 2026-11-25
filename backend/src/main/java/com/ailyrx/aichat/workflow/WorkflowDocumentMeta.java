package com.ailyrx.aichat.workflow;

/**
 * 导出公文时需要的文档元数据，不包含任何密钥或上游信息。
 */
public record WorkflowDocumentMeta(
        String title,
        String docType,
        String recipient,
        String signature,
        String date) {
}
