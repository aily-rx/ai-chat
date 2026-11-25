package com.ailyrx.aichat.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dify Chatflow 应用配置。知识库数据集 API 与问答应用 API 使用不同的 Key。
 */
@ConfigurationProperties(prefix = "app.dify-chat")
public record DifyChatProperties(
        String baseUrl,
        String appAKey,
        String appBKey,
        int connectTimeoutSeconds,
        int requestTimeoutSeconds) {

    public DifyChatProperties {
        baseUrl = normalize(baseUrl);
        appAKey = normalize(appAKey);
        appBKey = normalize(appBKey);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
