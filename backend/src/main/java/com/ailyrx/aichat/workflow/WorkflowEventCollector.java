package com.ailyrx.aichat.workflow;

import com.ailyrx.aichat.rag.CitationCollector;
import com.ailyrx.aichat.rag.CitationItem;
import com.ailyrx.aichat.rag.DifyRetrieverResource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects node snapshots, tool outputs and retrieval citations from Dify workflow events.
 */
final class WorkflowEventCollector {

    private final Map<String, WorkflowNodeSnapshot> nodes = new LinkedHashMap<>();
    private final Map<String, Object> toolResults = new LinkedHashMap<>();
    private List<CitationItem> citations = List.of();

    void accept(WorkflowNodeEvent event) {
        if (event.nodeId() == null) {
            return;
        }
        nodes.put(event.nodeId(), new WorkflowNodeSnapshot(
                event.nodeId(), event.name(), event.nodeType(), event.status()));
        if (event.outputs() == null || event.outputs().isEmpty()) {
            return;
        }
        if ("retrieval".equals(event.nodeId())) {
            citations = CitationCollector.collectResources(parseResources(event.outputs()));
            return;
        }
        String key = switch (event.nodeId()) {
            case "format_parse", "format_fallback" -> "format";
            case "terms_parse", "terms_fallback" -> "terms";
            default -> null;
        };
        if (key == null) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>(event.outputs());
        entry.put("nodeId", event.nodeId());
        entry.put("status", event.status());
        if (event.nodeId().endsWith("fallback")) {
            entry.put("fallback", true);
        }
        toolResults.put(key, entry);
    }

    List<WorkflowNodeSnapshot> nodes() {
        return new ArrayList<>(nodes.values());
    }

    Map<String, Object> toolResults() {
        return new LinkedHashMap<>(toolResults);
    }

    List<CitationItem> citations() {
        return List.copyOf(citations);
    }

    /**
     * Dify knowledge-retrieval 输出在不同版本可能为数组或 {"value":[...]}；
     * 条目字段可能位于 metadata 或顶层，均做兼容读取。
     */
    static List<DifyRetrieverResource> parseResources(Map<String, Object> outputs) {
        Object result = outputs.get("result");
        if (result instanceof Map<?, ?> wrapper) {
            result = wrapper.get("value");
        }
        if (!(result instanceof List<?> items)) {
            return List.of();
        }
        List<DifyRetrieverResource> resources = new ArrayList<>();
        for (Object raw : items) {
            Map<String, Object> item = mapValue(raw);
            if (item.isEmpty()) {
                continue;
            }
            Map<String, Object> metadata = mapValue(item.get("metadata"));
            String datasetId = firstText(metadata.get("dataset_id"), item.get("dataset_id"));
            String documentId = firstText(metadata.get("document_id"), item.get("document_id"));
            String documentName = firstText(
                    metadata.get("document_name"), item.get("document_name"), item.get("title"));
            Integer segmentPosition = firstInt(
                    metadata.get("segment_position"), item.get("segment_position"));
            Double score = firstDouble(metadata.get("score"), item.get("score"));
            String content = text(item.get("content"));
            resources.add(new DifyRetrieverResource(
                    datasetId, documentId, documentName, segmentPosition, content, score));
        }
        return resources;
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key instanceof String name) {
                mapped.put(name, item);
            }
        });
        return mapped;
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            String text = text(value);
            if (text != null && !text.isBlank()) {
                return text.strip();
            }
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer firstInt(Object... values) {
        for (Object value : values) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return Integer.parseInt(text.strip());
                } catch (NumberFormatException ignored) {
                    // 尝试下一个字段。
                }
            }
        }
        return null;
    }

    private static Double firstDouble(Object... values) {
        for (Object value : values) {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return Double.parseDouble(text.strip());
                } catch (NumberFormatException ignored) {
                    // 尝试下一个字段。
                }
            }
        }
        return null;
    }
}
