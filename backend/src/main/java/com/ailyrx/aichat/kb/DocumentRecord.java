package com.ailyrx.aichat.kb;

import java.time.Instant;

/**
 * 知识库文档元数据；S1 只保存在内存中，S2 接入 Dify 后补充远端文档信息。
 */
public record DocumentRecord(
        String id,
        String fileName,
        String contentType,
        long size,
        DocumentStatus status,
        Integer chunkCount,
        Instant createdAt,
        String errorMessage,
        String difyDatasetId,
        String difyDocumentId) {

    public DocumentRecord withIndexing(String datasetId, String documentId) {
        return new DocumentRecord(
                id, fileName, contentType, size, DocumentStatus.INDEXING,
                null, createdAt, null, datasetId, documentId);
    }

    public DocumentRecord withReady(int chunks) {
        return new DocumentRecord(
                id, fileName, contentType, size, DocumentStatus.READY,
                chunks, createdAt, null, difyDatasetId, difyDocumentId);
    }

    public DocumentRecord withFailed(String message) {
        return new DocumentRecord(
                id, fileName, contentType, size, DocumentStatus.FAILED,
                chunkCount, createdAt, message, difyDatasetId, difyDocumentId);
    }
}
