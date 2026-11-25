package com.ailyrx.aichat.rag;

/**
 * Dify message_end.metadata.retriever_resources 中与引用相关的字段。
 */
public record DifyRetrieverResource(
        String datasetId,
        String documentId,
        String documentName,
        Integer segmentPosition,
        String content,
        Double score) {
}
