package com.ailyrx.aichat.workflow;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 进程内运行状态存储。仅为单实例演示，重启后清空。
 */
@Component
public class WorkflowRunStore {

    private final ConcurrentMap<String, WorkflowRunState> runs = new ConcurrentHashMap<>();

    public void save(WorkflowRunState state) {
        runs.put(state.getRunId(), state);
    }

    public Optional<WorkflowRunState> find(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(runs.get(runId));
    }
}
