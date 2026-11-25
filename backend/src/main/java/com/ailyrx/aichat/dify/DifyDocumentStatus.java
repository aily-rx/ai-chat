package com.ailyrx.aichat.dify;

public record DifyDocumentStatus(
        String indexingStatus,
        Integer segmentCount,
        String error) {
}
