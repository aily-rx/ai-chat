package com.ailyrx.aichat.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ailyrx.aichat.dify.DifyClient;
import com.ailyrx.aichat.dify.DifyDocumentStatus;
import com.ailyrx.aichat.dify.DifyException;
import com.ailyrx.aichat.dify.DifyProperties;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DifyIndexingPollerTest {

    private InMemoryDocumentStore store;
    private DifyClient client;
    private DifyIndexingPoller poller;

    @BeforeEach
    void setUp() {
        store = new InMemoryDocumentStore();
        client = mock(DifyClient.class);
        poller = new DifyIndexingPoller(client, store, properties(60000));
    }

    @AfterEach
    void tearDown() {
        poller.shutdown();
    }

    @Test
    void completedMarksReady() {
        store.save(indexingDocument("doc-local", "ds-b", "dify-doc"));
        when(client.getDocumentStatus("ds-b", "dify-doc", "trace"))
                .thenReturn(new DifyDocumentStatus("completed", 4, null));

        poller.pollOnce("doc-local", "trace");

        DocumentRecord result = store.findById("doc-local").orElseThrow();
        assertEquals(DocumentStatus.READY, result.status());
        assertEquals(4, result.chunkCount());
        assertNull(result.errorMessage());
    }

    @Test
    void errorMarksFailedWithSanitizedMessage() {
        store.save(indexingDocument("doc-local", "ds-b", "dify-doc"));
        when(client.getDocumentStatus("ds-b", "dify-doc", "trace"))
                .thenReturn(new DifyDocumentStatus("error", 0, "upstream stack trace"));

        poller.pollOnce("doc-local", "trace");

        DocumentRecord result = store.findById("doc-local").orElseThrow();
        assertEquals(DocumentStatus.FAILED, result.status());
        assertEquals("知识库索引失败，请检查文件后重试", result.errorMessage());
        assertFalse(result.errorMessage().contains("upstream"));
    }

    @Test
    void timeoutMarksFailed() {
        poller.shutdown();
        poller = new DifyIndexingPoller(client, store, properties(0));
        store.save(indexingDocument("doc-local", "ds-b", "dify-doc"));

        poller.pollOnce("doc-local", "trace");

        DocumentRecord result = store.findById("doc-local").orElseThrow();
        assertEquals(DocumentStatus.FAILED, result.status());
        assertEquals("索引超时，请稍后重试", result.errorMessage());
    }

    @Test
    void statusQueryFailureKeepsIndexing() {
        store.save(indexingDocument("doc-local", "ds-b", "dify-doc"));
        when(client.getDocumentStatus(anyString(), anyString(), anyString()))
                .thenThrow(new DifyException("知识库服务暂不可用，请稍后重试", 503));

        poller.pollOnce("doc-local", "trace");

        DocumentRecord result = store.findById("doc-local").orElseThrow();
        assertEquals(DocumentStatus.INDEXING, result.status());
    }

    @Test
    void missingRemoteIdsMarksFailed() {
        DocumentRecord document = indexingDocument("doc-local", null, null);

        poller.startPolling(document, "trace");

        DocumentRecord result = store.findById("doc-local").orElseThrow();
        assertEquals(DocumentStatus.FAILED, result.status());
        assertEquals("文档未关联知识库，请重新上传", result.errorMessage());
    }

    private static DocumentRecord indexingDocument(
            String id, String datasetId, String documentId) {
        return new DocumentRecord(
                id, "notice.txt", "text/plain", 5, DocumentStatus.INDEXING,
                null, Instant.now(), null, datasetId, documentId);
    }

    private static DifyProperties properties(int pollTimeoutMs) {
        return new DifyProperties(
                "http://localhost/v1", "test-key", "ds-b", "ds-a", "ds-b",
                1000, pollTimeoutMs, 1, 5, "\n\n", 800, 100);
    }
}
