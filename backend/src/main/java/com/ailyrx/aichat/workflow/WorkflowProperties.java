package com.ailyrx.aichat.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dify Workflow 应用配置。Key 只从后端本地 .env 读取，不返回前端。
 */
@ConfigurationProperties(prefix = "app.workflow")
public record WorkflowProperties(
        String baseUrl,
        String apiKey,
        int connectTimeoutSeconds,
        int requestTimeoutSeconds) {

    public WorkflowProperties {
        baseUrl = normalize(baseUrl);
        apiKey = normalize(apiKey);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
