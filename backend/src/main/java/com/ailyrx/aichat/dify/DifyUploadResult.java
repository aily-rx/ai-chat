package com.ailyrx.aichat.dify;

public record DifyUploadResult(
        String documentId,
        String documentName,
        String batchId,
        String indexingStatus) {
}
