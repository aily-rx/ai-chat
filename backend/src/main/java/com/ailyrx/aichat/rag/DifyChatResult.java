package com.ailyrx.aichat.rag;

import java.util.List;

/**
 * 一次 Dify Chatflow 流式调用的可观测结果。
 */
public record DifyChatResult(String messageId, List<DifyRetrieverResource> resources) {

    public DifyChatResult {
        resources = resources == null ? List.of() : List.copyOf(resources);
    }
}
