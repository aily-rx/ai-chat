package com.ailyrx.aichat.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.ailyrx.aichat.rag.CitationItem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowEventCollectorTest {

    @Test
    void collectsCitationsFromWrappedRetrievalOutputAndDeduplicates() {
        WorkflowEventCollector collector = new WorkflowEventCollector();
        collector.accept(new WorkflowNodeEvent("retrieval", "知识检索", "knowledge-retrieval", "succeeded",
                Map.of("result", Map.of("value", List.of(
                        resource("doc-1", "安全生产检查通知.docx", 2, 0.8123, "第一段内容"),
                        resource("doc-1", "安全生产检查通知.docx", 2, 0.9123, "重复片段更高质量"),
                        resource("doc-2", "政务公开办法.pdf", 7, 0.7333, "第二份材料内容"))))));

        List<CitationItem> citations = collector.citations();

        assertThat(citations).hasSize(2);
        CitationItem first = citations.get(0);
        assertThat(first.docId()).isEqualTo("doc-1");
        assertThat(first.docName()).isEqualTo("安全生产检查通知.docx");
        assertThat(first.segmentIndex()).isEqualTo(2);
        assertThat(first.score()).isEqualTo(0.9123);
        assertThat(first.excerpt()).isEqualTo("重复片段更高质量");
        assertThat(citations.get(1).docId()).isEqualTo("doc-2");
    }

    @Test
    void collectsCitationsFromPlainListAndTopLevelFallbacks() {
        WorkflowEventCollector collector = new WorkflowEventCollector();
        collector.accept(new WorkflowNodeEvent("retrieval", "知识检索", "knowledge-retrieval", "succeeded",
                Map.of("result", List.of(Map.of(
                        "title", "术语规范.pdf",
                        "content", "公文用语应规范。",
                        "document_id", "doc-3",
                        "segment_position", 4,
                        "score", "0.5")))));

        List<CitationItem> citations = collector.citations();

        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).docId()).isEqualTo("doc-3");
        assertThat(citations.get(0).docName()).isEqualTo("术语规范.pdf");
        assertThat(citations.get(0).segmentIndex()).isEqualTo(4);
        assertThat(citations.get(0).score()).isEqualTo(0.5);
        assertThat(citations.get(0).excerpt()).isEqualTo("公文用语应规范。");
    }

    @Test
    void ignoresUnsupportedRetrievalOutputShape() {
        WorkflowEventCollector collector = new WorkflowEventCollector();
        collector.accept(new WorkflowNodeEvent("retrieval", "知识检索", "knowledge-retrieval", "succeeded",
                Map.of("result", "not-a-list")));

        assertThat(collector.citations()).isEmpty();
    }

    private Map<String, Object> resource(
            String documentId, String documentName, int position, double score, String content) {
        return Map.of(
                "title", documentName,
                "content", content,
                "metadata", Map.of(
                        "dataset_id", "dataset-1",
                        "document_id", documentId,
                        "document_name", documentName,
                        "segment_position", position,
                        "score", score));
    }
}
