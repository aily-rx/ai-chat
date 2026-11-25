package com.ailyrx.aichat.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CitationCollectorTest {

    private final CitationCollector collector = new CitationCollector();

    @Test
    void mapsDeduplicatesAndSortsCitations() {
        DifyRetrieverResource stale = new DifyRetrieverResource(
                "ds-b", "doc-1", "通知.pdf", 2, "旧片段", 0.50);
        DifyRetrieverResource best = new DifyRetrieverResource(
                "ds-b", "doc-1", "通知.pdf", 2, "片段二\n更新", 0.90);
        DifyRetrieverResource second = new DifyRetrieverResource(
                "ds-b", "doc-2", "办法.pdf", 1, "片段一", 0.70);
        DifyRetrieverResource withoutDocId = new DifyRetrieverResource(
                "ds-b", null, "无ID.pdf", 3, "片段三", null);
        DifyRetrieverResource missingSegment = new DifyRetrieverResource(
                "ds-b", "doc-3", "无效.pdf", null, "片段", 0.99);
        DifyRetrieverResource missingContent = new DifyRetrieverResource(
                "ds-b", "doc-4", "无效2.pdf", 1, "  ", 0.98);

        List<CitationItem> citations = collector.collect(List.of(
                stale, best, second, withoutDocId, missingSegment, missingContent));

        assertThat(citations).hasSize(3);
        CitationItem first = citations.get(0);
        assertThat(first.docId()).isEqualTo("doc-1");
        assertThat(first.docName()).isEqualTo("通知.pdf");
        assertThat(first.segmentIndex()).isEqualTo(2);
        assertThat(first.excerpt()).isEqualTo("片段二 更新");
        assertThat(first.page()).isNull();
        assertThat(first.score()).isEqualTo(0.90);

        assertThat(citations.get(1).docId()).isEqualTo("doc-2");
        assertThat(citations.get(2).docId()).startsWith("auto-");
        assertThat(citations.get(2).score()).isZero();
    }

    @Test
    void truncatesExcerptToFiveHundredCodePoints() {
        String excerpt = CitationCollector.truncate(
                "你".repeat(505), CitationCollector.MAX_EXCERPT_LENGTH);

        assertThat(excerpt).isEqualTo("你".repeat(500));
    }

    @Test
    void fallbackDocIdIsStableForSameDocumentAndSegment() {
        String first = CitationCollector.fallbackDocId("通知.pdf", 2);
        String second = CitationCollector.fallbackDocId("通知.pdf", 2);
        String other = CitationCollector.fallbackDocId("通知.pdf", 3);

        assertThat(first).isEqualTo(second).startsWith("auto-");
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void detectsConfiguredRefusalMarkers() {
        assertThat(RagService.isRefusal("资料不足，无法回答该问题。")).isTrue();
        assertThat(RagService.isRefusal("资料不足\n无法回答")).isTrue();
        assertThat(RagService.isRefusal("根据《通知》，2027 年 1 月 1 日放假。")).isFalse();
    }
}
