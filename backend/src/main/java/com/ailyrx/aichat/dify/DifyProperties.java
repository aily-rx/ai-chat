package com.ailyrx.aichat.dify;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.dify")
public record DifyProperties(
        String baseUrl,
        String apiKey,
        String defaultDatasetId,
        String datasetAId,
        String datasetBId,
        int pollIntervalMs,
        int pollTimeoutMs,
        int connectTimeoutSeconds,
        int requestTimeoutSeconds,
        String separator,
        int maxTokens,
        int chunkOverlap) {
}
