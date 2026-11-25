package com.ailyrx.aichat.rag;

/**
 * 对外引用结构。page 在 Dify 当前版本不可得，固定为 null，不伪造页码。
 */
public record CitationItem(
        String docId,
        String docName,
        int segmentIndex,
        String excerpt,
        Integer page,
        double score) {
}
