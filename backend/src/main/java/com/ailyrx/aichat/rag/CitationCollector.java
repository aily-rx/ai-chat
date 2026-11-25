package com.ailyrx.aichat.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 把 Dify 检索资源映射成前端引用，负责字段补齐、去重和排序。
 */
@Component
public class CitationCollector {

    static final int MAX_EXCERPT_LENGTH = 500;

    public List<CitationItem> collect(List<DifyRetrieverResource> resources) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }

        Map<String, CitationItem> deduplicated = new HashMap<>();
        for (DifyRetrieverResource resource : resources) {
            CitationItem item = toCitation(resource);
            if (item == null) {
                continue;
            }
            String key = item.docId() + "|" + item.segmentIndex();
            deduplicated.merge(key, item,
                    (existing, candidate) -> candidate.score() > existing.score() ? candidate : existing);
        }

        return deduplicated.values().stream()
                .sorted(Comparator.comparingDouble(CitationItem::score).reversed()
                        .thenComparing(CitationItem::docName)
                        .thenComparingInt(CitationItem::segmentIndex))
                .toList();
    }

    private CitationItem toCitation(DifyRetrieverResource resource) {
        if (resource == null
                || isBlank(resource.documentName())
                || resource.segmentPosition() == null
                || isBlank(resource.content())) {
            return null;
        }

        String excerpt = truncate(normalizeWhitespace(resource.content()), MAX_EXCERPT_LENGTH);
        if (excerpt.isBlank()) {
            return null;
        }

        String docId = isBlank(resource.documentId())
                ? fallbackDocId(resource.documentName(), resource.segmentPosition())
                : resource.documentId().strip();
        double score = resource.score() == null ? 0.0d : resource.score();

        return new CitationItem(
                docId,
                resource.documentName().strip(),
                resource.segmentPosition(),
                excerpt,
                null,
                score);
    }

    private static String normalizeWhitespace(String content) {
        StringBuilder normalized = new StringBuilder(content.length());
        boolean pendingSpace = false;
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (Character.isWhitespace(character)) {
                pendingSpace = normalized.length() > 0;
            } else {
                if (pendingSpace) {
                    normalized.append(' ');
                }
                normalized.append(character);
                pendingSpace = false;
            }
        }
        return normalized.toString();
    }

    static String truncate(String text, int maxCodePoints) {
        int codePointCount = text.codePointCount(0, text.length());
        if (codePointCount <= maxCodePoints) {
            return text;
        }
        int endIndex = text.offsetByCodePoints(0, maxCodePoints);
        return text.substring(0, endIndex);
    }

    static String fallbackDocId(String documentName, int segmentPosition) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((documentName + "|" + segmentPosition).getBytes(StandardCharsets.UTF_8));
            return "auto-" + java.util.HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
