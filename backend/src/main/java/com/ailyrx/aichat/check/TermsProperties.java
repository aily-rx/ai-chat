package com.ailyrx.aichat.check;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 术语表覆盖配置，格式为 app.terms.overrides.{原词}={建议词}。
 */
@ConfigurationProperties(prefix = "app.terms")
public record TermsProperties(Map<String, String> overrides) {

    public TermsProperties {
        overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }
}
