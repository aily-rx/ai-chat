package com.ailyrx.aichat.rag;

import java.util.List;

/**
 * SSE citations 事件的数据体。
 */
public record CitationPayload(List<CitationItem> items) {

    public CitationPayload {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
