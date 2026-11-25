package com.ailyrx.aichat.kb;

import com.ailyrx.aichat.dify.DifyClient;
import com.ailyrx.aichat.dify.DifyDocumentStatus;
import com.ailyrx.aichat.dify.DifyException;
import com.ailyrx.aichat.dify.DifyProperties;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 轮询 Dify 文档索引状态，并把结果写回本地文档状态。
 */
@Component
public class DifyIndexingPoller {

    private static final Logger log = LoggerFactory.getLogger(DifyIndexingPoller.class);

    private final DifyClient difyClient;
    private final DocumentStore documentStore;
    private final DifyProperties properties;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private final Map<String, Long> deadlines = new ConcurrentHashMap<>();

    public DifyIndexingPoller(
            DifyClient difyClient,
            DocumentStore documentStore,
            DifyProperties properties) {
        this.difyClient = difyClient;
        this.documentStore = documentStore;
        this.properties = properties;
        this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "dify-index-poller");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void startPolling(DocumentRecord document, String traceId) {
        if (document.difyDatasetId() == null || document.difyDocumentId() == null) {
            markFailed(document, "文档未关联知识库，请重新上传", traceId);
            return;
        }
        long interval = Math.max(properties.pollIntervalMs(), 200);
        long timeout = properties.pollTimeoutMs();
        if (timeout <= 0) {
            markFailed(document, "索引超时，请稍后重试", traceId);
            return;
        }
        deadlines.put(document.id(), System.currentTimeMillis() + timeout);
        tasks.computeIfAbsent(document.id(), id -> scheduler.scheduleWithFixedDelay(
                () -> pollSafely(id, traceId), interval, interval, TimeUnit.MILLISECONDS));
    }

    void pollOnce(String documentId, String traceId) {
        Optional<DocumentRecord> found = documentStore.findById(documentId);
        if (found.isEmpty() || found.get().status() != DocumentStatus.INDEXING) {
            stop(documentId);
            return;
        }

        DocumentRecord current = found.get();
        long timeout = properties.pollTimeoutMs();
        if (timeout <= 0) {
            markFailed(current, "索引超时，请稍后重试", traceId);
            return;
        }
        long deadline = deadlines.computeIfAbsent(
                documentId, id -> System.currentTimeMillis() + timeout);
        if (System.currentTimeMillis() >= deadline) {
            markFailed(current, "索引超时，请稍后重试", traceId);
            return;
        }

        DifyDocumentStatus status;
        try {
            status = difyClient.getDocumentStatus(
                    current.difyDatasetId(), current.difyDocumentId(), traceId);
        } catch (DifyException exception) {
            log.warn("kb indexing status query failed traceId={} documentId={} upstreamStatus={}",
                    traceId, documentId, exception.upstreamStatus());
            return;
        }

        String indexingStatus = status.indexingStatus();
        if ("completed".equals(indexingStatus)) {
            int chunkCount = status.segmentCount() == null ? 0 : status.segmentCount();
            documentStore.save(current.withReady(chunkCount));
            log.info("kb document ready traceId={} documentId={} chunkCount={}",
                    traceId, documentId, chunkCount);
            stop(documentId);
        } else if (isFailureStatus(indexingStatus)) {
            documentStore.save(current.withFailed("知识库索引失败，请检查文件后重试"));
            log.warn("kb document failed traceId={} documentId={} status={} upstreamError={}",
                    traceId, documentId, indexingStatus, status.error());
            stop(documentId);
        } else {
            log.debug("kb document indexing traceId={} documentId={} status={}",
                    traceId, documentId, indexingStatus);
        }
    }

    private void pollSafely(String documentId, String traceId) {
        try {
            pollOnce(documentId, traceId);
        } catch (RuntimeException exception) {
            log.warn("kb indexing poll error traceId={} documentId={} type={}",
                    traceId, documentId, exception.getClass().getSimpleName());
        }
    }

    private void markFailed(DocumentRecord current, String message, String traceId) {
        if (current.status() == DocumentStatus.INDEXING) {
            documentStore.save(current.withFailed(message));
            log.warn("kb document polling stopped traceId={} documentId={} reason={}",
                    traceId, current.id(), message);
        }
        stop(current.id());
    }

    private static boolean isFailureStatus(String status) {
        return "error".equals(status) || "paused".equals(status) || "stopped".equals(status);
    }

    private void stop(String documentId) {
        ScheduledFuture<?> task = tasks.remove(documentId);
        if (task != null) {
            task.cancel(false);
        }
        deadlines.remove(documentId);
    }

    @PreDestroy
    public void shutdown() {
        tasks.values().forEach(task -> task.cancel(false));
        tasks.clear();
        deadlines.clear();
        scheduler.shutdownNow();
    }
}
