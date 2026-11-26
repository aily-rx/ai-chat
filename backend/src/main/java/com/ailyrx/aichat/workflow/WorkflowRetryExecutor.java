package com.ailyrx.aichat.workflow;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;

/**
 * Runs at most one reject-driven regeneration per workflow run in the background.
 */
@Component
public class WorkflowRetryExecutor {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "workflow-retry");
        thread.setDaemon(true);
        return thread;
    });

    public void submit(Runnable task) {
        executor.execute(task);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
